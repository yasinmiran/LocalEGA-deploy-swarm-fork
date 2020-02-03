package no.neic.localega.deploy;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import lombok.extern.slf4j.Slf4j;
import no.uio.ifi.crypt4gh.stream.Crypt4GHOutputStream;
import no.uio.ifi.crypt4gh.util.KeyUtils;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Slf4j
public class IngestionTest {

    private KeyUtils keyUtils = KeyUtils.getInstance();

    private File rawFile;
    private File encFile;
    private KeyPair senderKeyPair;
    private KeyPair recipientKeyPair;
    private String stableId;

    @Before
    public void setup() throws IOException, GeneralSecurityException {
        long fileSize = 1024 * 1024 * 10;
        log.info("Generating " + fileSize + " bytes file to submit...");
        rawFile = new File(UUID.randomUUID().toString() + ".raw");
        RandomAccessFile randomAccessFile = new RandomAccessFile(rawFile, "rw");
        randomAccessFile.setLength(fileSize);
        randomAccessFile.close();
        byte[] bytes = DigestUtils.sha256(Files.newInputStream(rawFile.toPath()));
        log.info("Checksum: " + Hex.encodeHexString(bytes));

        log.info("Generating sender and recipient key-pairs...");
        senderKeyPair = keyUtils.generateKeyPair();
        recipientKeyPair = keyUtils.generateKeyPair();


        log.info("Encrypting the file with Crypt4GH...");
        encFile = new File(rawFile.getName() + ".enc");
        PublicKey localEGAInstancePublicKey = keyUtils.readPublicKey(new File("ega.pub.pem"));
        FileOutputStream fileOutputStream = new FileOutputStream(encFile);
        Crypt4GHOutputStream crypt4GHOutputStream = new Crypt4GHOutputStream(fileOutputStream, senderKeyPair.getPrivate(), localEGAInstancePublicKey);
        FileUtils.copyFile(rawFile, crypt4GHOutputStream);
        crypt4GHOutputStream.close();
    }

    @Test
    public void test() {
        try {
            upload();
            ingest();
            Thread.sleep(10000); // wait for ingestion and verification to be finished
            finalise();
            verify();
        } catch (Throwable t) {
            log.error(t.getMessage(), t);
            Assert.fail();
        }
    }

    private void upload() throws IOException {
        log.info("Uploading a file through a proxy...");
        Unirest.config().verifySsl(false);
        String token = Unirest
                .get("https://localhost/cega")
                .basicAuth("dummy", "dummy")
                .asString()
                .getBody();
        String md5Hex = DigestUtils.md5Hex(Files.newInputStream(encFile.toPath()));
        log.info("MD5 digest: {}", md5Hex);
        String uploadURL = String.format("https://localhost/stream/%s?md5=%s", encFile.getName(), md5Hex);
        JsonNode jsonResponse = Unirest
                .patch(uploadURL)
                .header("Authorization", "Bearer " + token)
                .body(FileUtils.readFileToByteArray(encFile))
                .asJson()
                .getBody();
        Assert.assertEquals(201, jsonResponse.getObject().getInt("statusCode"));
        String uploadId = jsonResponse.getObject().getString("id");
        log.info("Upload ID: {}", uploadId);
        String finalizeURL = String.format("https://localhost/stream/%s?uploadId=%s&chunk=end&md5=%s&fileSize=%s",
                encFile.getName(),
                uploadId,
                md5Hex,
                FileUtils.sizeOf(encFile));
        jsonResponse = Unirest
                .patch(finalizeURL)
                .header("Authorization", "Bearer " + token)
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


        stableId = "EGAF" + UUID.randomUUID().toString().replace("-", "");
        String message = String.format("{\"user\":\"%s\",\"filepath\":\"%s\",\"stable_id\":\"%s\"}", "dummy", encFile.getName(), stableId);
        log.info(message);
        channel.basicPublish("localega.v1",
                "files",
                properties,
                message.getBytes());

        channel.close();
        connectionFactory.close();
    }

    private void finalise() throws IOException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException, SQLException {
        log.info("Publishing finalization message to CentralEGA...");
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
        props.setProperty("sslcert", new File("localhost+6-client.pem").getAbsolutePath());
        props.setProperty("sslkey", new File("localhost+6-client-key.der").getAbsolutePath());
        java.sql.Connection conn = DriverManager.getConnection(url, props);
        String sql = "select id from local_ega.files where inbox_path = ?";
        PreparedStatement statement = conn.prepareStatement(sql);
        statement.setString(1, encFile.getName());
        ResultSet resultSet = statement.executeQuery();
        if (resultSet.wasNull() || !resultSet.next()) {
            Assert.fail("Verification failed");
        }
        int fileId = resultSet.getInt(1);

        log.info("File ID: {}", fileId);

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

        String message = String.format("{\"file_id\":\"%s\",\"stable_id\":\"%s\"}", fileId, stableId);
        log.info(message);
        channel.basicPublish("localega.v1",
                "stableIDs",
                properties,
                message.getBytes());

        channel.close();
        connectionFactory.close();
    }

//    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
//    private void finalise() throws SQLException {
//        log.info("Finalizing the submission...");
//        String dbHost = "localhost";
//        String port = "5432";
//        String db = "lega";
//        String url = String.format("jdbc:postgresql://%s:%s/%s", dbHost, port, db);
//        Properties props = new Properties();
//        props.setProperty("user", "lega_in");
//        props.setProperty("password", System.getenv("DB_LEGA_IN_PASSWORD"));
//        props.setProperty("ssl", "true");
//        props.setProperty("application_name", "LocalEGA");
//        props.setProperty("sslmode", "verify-full");
//        props.setProperty("sslrootcert", new File("rootCA.pem").getAbsolutePath());
//        props.setProperty("sslcert", new File("localhost+6-client.pem").getAbsolutePath());
//        props.setProperty("sslkey", new File("localhost+6-client-key.der").getAbsolutePath());
//        try {
//            java.sql.Connection conn = DriverManager.getConnection(url, props);
//            String sql = "update local_ega.files set stable_id = ? where inbox_path = ?";
//            PreparedStatement statement = conn.prepareStatement(sql);
//            statement.setString(1, stableId);
//            statement.setString(2, encFile.getName());
//            statement.executeUpdate();
//        } catch (SQLException e) {
//            log.error(e.getMessage(), e);
//            throw e;
//        }
//        log.info("Submission finalized successfully");
//    }

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
        props.setProperty("sslcert", new File("localhost+6-client.pem").getAbsolutePath());
        props.setProperty("sslkey", new File("localhost+6-client-key.der").getAbsolutePath());
        java.sql.Connection conn = DriverManager.getConnection(url, props);
        String sql = "select status from local_ega.files where status = 'READY' AND inbox_path = ?";
        PreparedStatement statement = conn.prepareStatement(sql);
        statement.setString(1, encFile.getName());
        ResultSet resultSet = statement.executeQuery();
        if (resultSet.wasNull() || !resultSet.next()) {
            Assert.fail("Verification failed");
        }
        log.info("Verification completed successfully");
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @After
    public void teardown() {
        rawFile.delete();
        encFile.delete();
    }

}
