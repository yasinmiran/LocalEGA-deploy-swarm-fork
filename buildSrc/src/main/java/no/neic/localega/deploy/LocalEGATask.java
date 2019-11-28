package no.neic.localega.deploy;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.gradle.api.DefaultTask;

import java.security.Security;

@Slf4j
public abstract class LocalEGATask extends DefaultTask {

    public LocalEGATask() {
        Security.addProvider(new BouncyCastleProvider());
        setGroup("LocalEGA");
    }

    protected String getProperty(String key) {
        return (String) getProject().getProperties().getOrDefault(key, null);
    }

}
