package no.neic.localega.deploy;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import no.uio.ifi.crypt4gh.stream.Crypt4GHOutputStream;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.openpgp.PGPException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
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
        rawFile = Files.createTempFile("data", ".raw").toFile();
        RandomAccessFile randomAccessFile = new RandomAccessFile(rawFile, "rw");
        randomAccessFile.setLength(fileSize);
        randomAccessFile.close();
        byte[] bytes = DigestUtils.sha256(FileUtils.openInputStream(rawFile));
        log.info("Checksum: " + Hex.encodeHexString(bytes));

        log.info("Encrypting the file with Crypt4GH...");
        encFile = Files.createTempFile(rawFile.getName(), ".enc").toFile();
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
    public void test() throws IOException, URISyntaxException, NoSuchAlgorithmException, TimeoutException, KeyManagementException {
        upload(System.getenv("TRYGGVE_IP_ADDRESS"));
        ingest(System.getenv("CEGA_CONNECTION"));
    }

    private void upload(String host) throws IOException, URISyntaxException {
        log.info("Connecting to " + host);
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        ssh.connect(host, 2222);
        ssh.authPublickey("dummy", new File(IOUtils.resourceToURL("/dummy.sec").toURI()).getAbsolutePath());
        log.info("Uploading a file...");
        SFTPClient client = ssh.newSFTPClient();
        client.put(encFile.getAbsolutePath(), "data.raw.enc");
        ssh.close();
    }

    private void ingest(String mqConnectionString) throws IOException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
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
        channel.basicPublish("localega.v1",
                "files",
                properties,
                String.format("{\"user\":\"%s\",\"filepath\":\"data.raw.enc\",\"stable_id\":\"%s\"}", "dummy", stableId).getBytes());

        channel.close();
        connectionFactory.close();
    }

    @After
    public void teardown() {
        rawFile.delete();
        encFile.delete();
    }

}
