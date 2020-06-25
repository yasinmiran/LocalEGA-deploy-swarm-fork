package no.neic.localega.deploy;

import com.auth0.jwt.algorithms.Algorithm;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import lombok.extern.slf4j.Slf4j;
import no.uio.ifi.crypt4gh.stream.Crypt4GHInputStream;
import no.uio.ifi.crypt4gh.stream.Crypt4GHOutputStream;
import no.uio.ifi.crypt4gh.util.KeyUtils;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Slf4j
public class IngestionTest {

    public static final String BEGIN_PUBLIC_KEY = "-----BEGIN PUBLIC KEY-----";
    public static final String END_PUBLIC_KEY = "-----END PUBLIC KEY-----";
    public static final String BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----";
    public static final String END_PRIVATE_KEY = "-----END PRIVATE KEY-----";

    private KeyUtils keyUtils = KeyUtils.getInstance();

    private File rawFile;
    private File encFile;
    private String rawSHA256Checksum;
    private String encSHA256Checksum;
    private String stableId;
    private String datasetId;
    private String archivePath;

    @Before
    public void setup() throws IOException, GeneralSecurityException {
        long fileSize = 1024 * 1024 * 10;
        log.info("Generating " + fileSize + " bytes file to submit...");
        rawFile = new File(UUID.randomUUID().toString() + ".raw");
        RandomAccessFile randomAccessFile = new RandomAccessFile(rawFile, "rw");
        randomAccessFile.setLength(fileSize);
        randomAccessFile.close();
        byte[] bytes = DigestUtils.sha256(Files.newInputStream(rawFile.toPath()));
        rawSHA256Checksum = Hex.encodeHexString(bytes);
        log.info("Raw SHA256 checksum: " + rawSHA256Checksum);

        log.info("Generating sender and recipient key-pairs...");
        KeyPair senderKeyPair = keyUtils.generateKeyPair();

        log.info("Encrypting the file with Crypt4GH...");
        encFile = new File(rawFile.getName() + ".enc");
        PublicKey localEGAInstancePublicKey = keyUtils.readPublicKey(new File("ega.pub.pem"));
        try (FileOutputStream fileOutputStream = new FileOutputStream(encFile);
             Crypt4GHOutputStream crypt4GHOutputStream = new Crypt4GHOutputStream(fileOutputStream, senderKeyPair.getPrivate(), localEGAInstancePublicKey)) {
            FileUtils.copyFile(rawFile, crypt4GHOutputStream);
        }

        bytes = DigestUtils.sha256(Files.newInputStream(encFile.toPath()));
        encSHA256Checksum = Hex.encodeHexString(bytes);
        log.info("Enc SHA256 checksum: " + encSHA256Checksum);

        Unirest.primaryInstance().config().verifySsl(false).hostnameVerifier(NoopHostnameVerifier.INSTANCE);
    }

    @Test
    public void test() {
        try {
            upload();
            ingest();
            Thread.sleep(10000); // wait for ingestion and verification to be finished
            verify();
            map();
            download();
        } catch (Throwable t) {
            log.error(t.getMessage(), t);
            Assert.fail();
        }
    }

    private void upload() throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
        log.info("Uploading a file through a proxy...");
        String token = generateVisaToken("upload");
        log.info("Visa JWT token: {}", token);
        String md5Hex = DigestUtils.md5Hex(Files.newInputStream(encFile.toPath()));
        log.info("Encrypted MD5 checksum: {}", md5Hex);
        String uploadURL = String.format("https://localhost/stream/%s?md5=%s", encFile.getName(), md5Hex);
        JsonNode jsonResponse = Unirest
                .patch(uploadURL)
                .basicAuth("dummy", "dummy")
                .header("Proxy-Authorization", "Bearer " + token)
                .body(FileUtils.readFileToByteArray(encFile))
                .asJson()
                .getBody();
        String uploadId = jsonResponse.getObject().getString("id");
        log.info("Upload ID: {}", uploadId);
        String finalizeURL = String.format("https://localhost/stream/%s?uploadId=%s&chunk=end&sha256=%s&fileSize=%s",
                encFile.getName(),
                uploadId,
                encSHA256Checksum,
                FileUtils.sizeOf(encFile));
        jsonResponse = Unirest
                .patch(finalizeURL)
                .basicAuth("dummy", "dummy")
                .header("Proxy-Authorization", "Bearer " + token)
                .asJson()
                .getBody();
        Assert.assertEquals(201, jsonResponse.getObject().getInt("statusCode"));
    }

    private void ingest() throws IOException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
        log.info("Publishing ingestion message to CentralEGA...");
        String mqConnectionString = System.getenv("CEGA_CONNECTION");
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(mqConnectionString);
        Connection connectionFactory = factory.newConnection();
        Channel channel = connectionFactory.createChannel();
        AMQP.BasicProperties properties = new AMQP.BasicProperties()
                .builder()
                .deliveryMode(2)
                .contentType("application/json")
                .contentEncoding(StandardCharsets.UTF_8.displayName())
                .correlationId(UUID.randomUUID().toString())
                .build();

        String message = String.format("{\"user\":\"%s\",\"filepath\":\"%s\"}", "dummy", encFile.getName());
        log.info(message);
        channel.basicPublish("localega.v1",
                "files",
                properties,
                message.getBytes());

        channel.close();
        connectionFactory.close();
    }

    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    private void verify() throws SQLException {
        log.info("Starting verification...");
        String dbHost = "localhost";
        String port = "5432";
        String db = "lega";
        String url = String.format("jdbc:postgresql://%s:%s/%s", dbHost, port, db);
        Properties props = new Properties();
        props.setProperty("user", "lega_in");
        props.setProperty("password", System.getenv("DB_LEGA_IN_PASSWORD"));
        props.setProperty("ssl", "true");
        props.setProperty("application_name", "LocalEGA");
        props.setProperty("sslmode", "verify-full");
        props.setProperty("sslrootcert", new File("rootCA.pem").getAbsolutePath());
        props.setProperty("sslcert", new File("localhost+9-client.pem").getAbsolutePath());
        props.setProperty("sslkey", new File("localhost+9-client-key.der").getAbsolutePath());
        java.sql.Connection conn = DriverManager.getConnection(url, props);
        String sql = "select * from local_ega.files where status = 'READY' AND inbox_path = ?";
        PreparedStatement statement = conn.prepareStatement(sql);
        statement.setString(1, encFile.getName());
        ResultSet resultSet = statement.executeQuery();
        if (resultSet.wasNull() || !resultSet.next()) {
            Assert.fail("Verification failed");
        }
        archivePath = resultSet.getString(8);
        stableId = resultSet.getString(13);
        log.info("Archive path: {}", archivePath);
        log.info("Verification completed successfully");
    }

    private void map() throws NoSuchAlgorithmException, KeyManagementException, URISyntaxException, IOException, TimeoutException {
        log.info("Mapping file to a dataset...");

        datasetId = "EGAD" + UUID.randomUUID().toString().replace("-", "");

        String mqConnectionString = String.format("amqps://%s:%s@localhost:5671/%%2F", System.getenv("PRIVATE_BROKER_USER"), System.getenv("PRIVATE_BROKER_PASSWORD"));
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(mqConnectionString);

        Connection connectionFactory = factory.newConnection();
        Channel channel = connectionFactory.createChannel();
        AMQP.BasicProperties properties = new AMQP.BasicProperties()
                .builder()
                .deliveryMode(2)
                .contentType("application/json")
                .contentEncoding(StandardCharsets.UTF_8.displayName())
                .correlationId(UUID.randomUUID().toString())
                .build();

        String message = String.format("{\"accession_ids\":[\"%s\"],\"dataset_id\":\"%s\"}", stableId, datasetId);
        log.info(message);
        channel.basicPublish("",
                "mappings",
                properties,
                message.getBytes());

        channel.close();
        connectionFactory.close();

        log.info("Permissions granted successfully");
    }

    private void download() throws GeneralSecurityException, IOException {
        String token = generateVisaToken(datasetId);
        log.info("Visa JWT token: {}", token);

        String datasets = Unirest
                .get("https://localhost:8080/metadata/datasets")
                .header("Authorization", "Bearer " + token)
                .asString()
                .getBody();
        Assert.assertEquals(String.format("[\"%s\"]", datasetId).strip(), datasets.strip());

        Assert.assertEquals(
                String.format(
                        "[{\"fileId\":\"%s\",\"datasetId\":\"%s\",\"displayFileName\":\"%s\",\"fileName\":\"%s\",\"fileSize\":10490240,\"unencryptedChecksum\":\"%s\",\"unencryptedChecksumType\":\"SHA256\",\"fileStatus\":\"READY\"}]\n",
                        stableId,
                        datasetId,
                        encFile.getName(),
                        archivePath,
                        rawSHA256Checksum).strip(),
                Unirest
                        .get(String.format("https://localhost:8080/metadata/datasets/%s/files", datasetId))
                        .header("Authorization", "Bearer " + token)
                        .asString()
                        .getBody().strip());

        byte[] file = Unirest
                .get(String.format("https://localhost:8080/files/%s", stableId))
                .header("Authorization", "Bearer " + token)
                .asBytes()
                .getBody();
        String obtainedChecksum = Hex.encodeHexString(DigestUtils.sha256(file));
        Assert.assertEquals(rawSHA256Checksum, obtainedChecksum);

        KeyPair recipientKeyPair = keyUtils.generateKeyPair();
        StringWriter stringWriter = new StringWriter();
        keyUtils.writeCrypt4GHKey(stringWriter, recipientKeyPair.getPublic(), null);
        String key = stringWriter.toString();
        file = Unirest
                .get(String.format("https://localhost:8080/files/%s?destinationFormat=CRYPT4GH", stableId))
                .header("Authorization", "Bearer " + token)
                .header("Public-Key", key)
                .asBytes()
                .getBody();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(file);
             Crypt4GHInputStream crypt4GHInputStream = new Crypt4GHInputStream(byteArrayInputStream, recipientKeyPair.getPrivate())) {
            IOUtils.copyLarge(crypt4GHInputStream, byteArrayOutputStream);
        }
        obtainedChecksum = Hex.encodeHexString(DigestUtils.sha256(byteArrayOutputStream.toByteArray()));
        Assert.assertEquals(rawSHA256Checksum, obtainedChecksum);
    }

    private String generateVisaToken(String resource) throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        RSAPublicKey publicKey = getPublicKey();
        RSAPrivateKey privateKey = getPrivateKey();
        byte[] visaHeader = Base64.getEncoder().encode(("{\n" +
                "  \"jku\": \"https://login.elixir-czech.org/oidc/jwk\",\n" +
                "  \"kid\": \"rsa1\",\n" +
                "  \"typ\": \"JWT\",\n" +
                "  \"alg\": \"RS256\"\n" +
                "}").getBytes());
        byte[] visaPayload = Base64.getEncoder().encode(String.format("{\n" +
                "  \"sub\": \"dummy@elixir-europe.org\",\n" +
                "  \"ga4gh_visa_v1\": {\n" +
                "    \"asserted\": 1583757401,\n" +
                "    \"by\": \"dac\",\n" +
                "    \"source\": \"https://login.elixir-czech.org/google-idp/\",\n" +
                "    \"type\": \"ControlledAccessGrants\",\n" +
                "    \"value\": \"https://ega.tsd.usit.uio.no/datasets/%s/\"\n" +
                "  },\n" +
                "  \"iss\": \"https://login.elixir-czech.org/oidc/\",\n" +
                "  \"exp\": 32503680000,\n" +
                "  \"iat\": 1583757671,\n" +
                "  \"jti\": \"f520d56f-e51a-431c-94e1-2a3f9da8b0c9\"\n" +
                "}", resource).getBytes());
        byte[] visaSignature = Algorithm.RSA256(publicKey, privateKey).sign(visaHeader, visaPayload);
        return new String(visaHeader) + "." + new String(visaPayload) + "." + Base64.getEncoder().encodeToString(visaSignature);
    }

    private RSAPublicKey getPublicKey() throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        String jwtPublicKey = FileUtils.readFileToString(new File("jwt.pub.pem"), Charset.defaultCharset());
        String encodedKey = jwtPublicKey
                .replace(BEGIN_PUBLIC_KEY, "")
                .replace(END_PUBLIC_KEY, "")
                .replace(System.lineSeparator(), "")
                .replace(" ", "")
                .trim();
        byte[] decodedKey = Base64.getDecoder().decode(encodedKey);
        return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(decodedKey));
    }

    private RSAPrivateKey getPrivateKey() throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        String jwtPublicKey = FileUtils.readFileToString(new File("jwt.priv.pem"), Charset.defaultCharset());
        String encodedKey = jwtPublicKey
                .replace(BEGIN_PRIVATE_KEY, "")
                .replace(END_PRIVATE_KEY, "")
                .replace(System.lineSeparator(), "")
                .replace(" ", "")
                .trim();
        byte[] decodedKey = Base64.getDecoder().decode(encodedKey);
        return (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decodedKey));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @After
    public void teardown() {
        rawFile.delete();
        encFile.delete();
    }

}
