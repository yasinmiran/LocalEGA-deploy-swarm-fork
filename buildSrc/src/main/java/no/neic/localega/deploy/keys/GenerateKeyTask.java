package no.neic.localega.deploy.keys;

import no.neic.localega.deploy.LocalEGATask;
import no.uio.ifi.crypt4gh.util.KeyUtils;
import org.apache.commons.io.FileUtils;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.nio.charset.Charset;
import java.security.KeyPair;

public class GenerateKeyTask extends LocalEGATask {

    @TaskAction
    public void run() throws Exception {
        String name = getProperty("id");
        String password = getProperty("password");
        KeyUtils keyUtils = KeyUtils.getInstance();
        KeyPair keyPair = KeyUtils.getInstance().generateKeyPair();
        File pubKey = getProject().file(name + ".pub");
        keyUtils.writeCrypt4GHKey(pubKey, keyPair.getPublic(), null);
        File secKey = getProject().file(name + ".sec");
        keyUtils.writeCrypt4GHKey(secKey, keyPair.getPrivate(), password.toCharArray());
        File egaSecPass = getProject().file(name + ".sec.pass");
        FileUtils.write(egaSecPass, password, Charset.defaultCharset());
    }

}
