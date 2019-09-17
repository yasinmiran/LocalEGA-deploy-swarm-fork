package no.neic.localega.deploy;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.minio.MinioClient;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import no.uio.ifi.crypt4gh.stream.Crypt4GHOutputStream;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.openpgp.PGPException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Slf4j
public class IngestionTest {

    private File rawFile;
    private File encFile;

    @Before
    public void setup() throws IOException, PGPException {
        long fileSize = 1024 * 1024 * 10;
        log.info("Generating " + fileSize + " bytes file to submit...");
        rawFile = new File(UUID.randomUUID().toString() + ".raw");
        RandomAccessFile randomAccessFile = new RandomAccessFile(rawFile, "rw");
        randomAccessFile.setLength(fileSize);
        randomAccessFile.close();
        byte[] bytes = DigestUtils.sha256(FileUtils.openInputStream(rawFile));
        log.info("Checksum: " + Hex.encodeHexString(bytes));

        log.info("Encrypting the file with Crypt4GH...");
        encFile = new File(rawFile.getName() + ".enc");
        byte[] digest = DigestUtils.sha256(FileUtils.openInputStream(rawFile));
        String key = FileUtils.readFileToString(new File("ega.pub"), Charset.defaultCharset());
        FileOutputStream fileOutputStream = new FileOutputStream(encFile);
        Crypt4GHOutputStream crypt4GHOutputStream = new Crypt4GHOutputStream(fileOutputStream, key, digest);
        String sessionKey = Hex.encodeHexString(crypt4GHOutputStream.getSessionKeyBytes());
        String iv = Hex.encodeHexString(crypt4GHOutputStream.getIvBytes());
        log.info("Session key: " + sessionKey);
        log.info("IV: " + iv);
        FileUtils.copyFile(rawFile, crypt4GHOutputStream);
        crypt4GHOutputStream.close();
    }

    @Test
    public void test() throws IOException, URISyntaxException, NoSuchAlgorithmException, TimeoutException, KeyManagementException, SQLException, InterruptedException, XmlPullParserException, InvalidKeyException, InvalidPortException, InvalidArgumentException, ErrorResponseException, NoResponseException, InvalidBucketNameException, InsufficientDataException, InvalidEndpointException, InternalException {
        try {
            upload(System.getenv("S3_ENDPOINT"), System.getenv("MINIO_ACCESS_KEY"), System.getenv("MINIO_SECRET_KEY"));
            ingest(System.getenv("CEGA_CONNECTION"));
            Thread.sleep(10000); // wait for ingestion and verification to be finished
            verify(System.getenv("TSD_IP_ADDRESS"));
        } catch (Throwable t) {
            log.error(t.getMessage(), t);
        }
    }

    private void upload(String s3Endpoint, String accessKey, String secretKey) throws IOException, InvalidPortException, InvalidEndpointException, InvalidKeyException, NoSuchAlgorithmException, InsufficientDataException, InvalidArgumentException, InternalException, NoResponseException, InvalidBucketNameException, XmlPullParserException, ErrorResponseException {
        log.info("Connecting to " + s3Endpoint);
        MinioClient minioClient = new MinioClient(s3Endpoint, accessKey, secretKey, false);
        log.info("Uploading a file...");
        minioClient.putObject("inbox", "dummy/" + encFile.getName(), encFile.getAbsolutePath());
    }

    private void ingest(String mqConnectionString) throws IOException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
        log.info("Publishing ingestion message to CentralEGA...");
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(mqConnectionString);
        Connection connectionFactory = factory.newConnection();
        Channel channel = connectionFactory.createChannel();
        AMQP.BasicProperties properties = new AMQP.BasicProperties()
                .builder()
                .deliveryMode(2)
                .contentType("application/json")
                .contentEncoding(StandardCharsets.UTF_8.displayName())
                .build();


        String stableId = "EGAF" + UUID.randomUUID().toString().replace("-", "");
        String message = String.format("{\"user\":\"%s\",\"filepath\":\"%s\",\"stable_id\":\"%s\"}", "dummy", encFile.getName(), stableId);
        log.info(message);
        channel.basicPublish("localega.v1",
                "files",
                properties,
                message.getBytes());

        channel.close();
        connectionFactory.close();
    }

    private void verify(String dbHost) throws SQLException {
        log.info("Starting verification...");
        String port = "5432";
        String db = "lega";
        String url = String.format("jdbc:postgresql://%s:%s/%s", dbHost, port, db);
        Properties props = new Properties();
        props.setProperty("user", "lega_in");
        props.setProperty("password", System.getenv("DB_LEGA_IN_PASSWORD"));
        props.setProperty("ssl", "true");
        props.setProperty("sslmode", "require");
        props.setProperty("application_name", "LocalEGA");
        props.setProperty("sslrootcert", new File("rootCA.pem").getAbsolutePath());
        props.setProperty("sslcert", new File("client-server.pem").getAbsolutePath());
        props.setProperty("sslkey", new File("client-server-key.der").getAbsolutePath());
        try {
            java.sql.Connection conn = DriverManager.getConnection(url, props);
            String sql = "select status from local_ega.files where status = 'COMPLETED' AND inbox_path = ?";
            PreparedStatement statement = conn.prepareStatement(sql);
            statement.setString(1, encFile.getName());
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.wasNull() || !resultSet.next()) {
                Assert.fail("Verification failed");
            }
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            throw e;
        }
        log.info("Verification completed successfully");
    }

    @After
    public void teardown() {
        rawFile.delete();
        encFile.delete();
    }

}
