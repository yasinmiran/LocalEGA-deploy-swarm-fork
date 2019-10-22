package no.neic.localega.deploy.mq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import no.neic.localega.deploy.LocalEGATask;
import org.gradle.api.tasks.TaskAction;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class IngestTask extends LocalEGATask {

    @TaskAction
    public void run() throws Exception {
        getLogger().lifecycle("\nPublishing ingestion message to CentralEGA...\n");
        String mqConnectionString = System.getenv("CEGA_CONNECTION");
        String user = getProperty("user");
        String file = getProperty("file");
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
        String message = String.format("{\"user\":\"%s\",\"filepath\":\"%s\",\"stable_id\":\"%s\"}", user, file, stableId);
        getLogger().lifecycle("\n" + message + "\n");
        channel.basicPublish("localega.v1",
                "files",
                properties,
                message.getBytes());

        channel.close();
        connectionFactory.close();
    }

}
