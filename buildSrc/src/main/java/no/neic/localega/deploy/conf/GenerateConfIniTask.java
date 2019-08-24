package no.neic.localega.deploy.conf;

import no.neic.localega.deploy.LocalEGATask;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.nio.charset.Charset;

public class GenerateConfIniTask extends LocalEGATask {

    @TaskAction
    public void run() throws Exception {
        String inboxLocation = System.getenv("INBOX_LOCATION");
        String vaultS3AccessKey = System.getenv("VAULT_S3_ACCESS_KEY");
        String vaultS3SecretKey = System.getenv("VAULT_S3_SECRET_KEY");
        String dbHost = System.getenv("DB_HOST");
        String dbPassword = System.getenv("DB_LEGA_IN_PASSWORD");
        String mqConnection = System.getenv("MQ_CONNECTION");
        String confIni = IOUtils.resourceToString("/default.conf.ini", Charset.defaultCharset());
        confIni = confIni.replace("INBOX_LOCATION", String.valueOf(inboxLocation));
        confIni = confIni.replace("VAULT_S3_ACCESS_KEY", String.valueOf(vaultS3AccessKey));
        confIni = confIni.replace("VAULT_S3_SECRET_KEY", String.valueOf(vaultS3SecretKey));
        confIni = confIni.replace("DB_HOST", String.valueOf(dbHost));
        confIni = confIni.replace("DB_LEGA_IN_PASSWORD", String.valueOf(dbPassword));
        confIni = confIni.replace("MQ_CONNECTION", String.valueOf(mqConnection));
        File confIniFile = getProject().file("conf.ini");
        FileUtils.write(confIniFile, confIni, Charset.defaultCharset());
    }

}
