package no.neic.localega.deploy;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.gradle.api.DefaultTask;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public abstract class LocalEGATask extends DefaultTask {

    public LocalEGATask() {
        Security.addProvider(new BouncyCastleProvider());
        setGroup("LocalEGA");
    }

    protected String getProperty(String key) {
        return (String) getProject().getProperties().getOrDefault(key, null);
    }

    protected X509Certificate readX509Certificate(String certificateFilePath) throws IOException, CertificateException {
        try (InputStream inStream = new FileInputStream(certificateFilePath)) {
            try (PEMParser pemParser = new PEMParser(new InputStreamReader(inStream))) {
                Object object = pemParser.readObject();
                if (object instanceof X509CertificateHolder) {
                    return new JcaX509CertificateConverter().getCertificate((X509CertificateHolder) object);
                }
            }
        }
        throw new RuntimeException("Can't read certificate file from file: " + certificateFilePath);
    }

    protected KeyPair readKeyPair(String keyPairFilePath) throws IOException {
        try (InputStream inStream = new FileInputStream(keyPairFilePath)) {
            try (PEMParser pemParser = new PEMParser(new InputStreamReader(inStream))) {
                Object object = pemParser.readObject();
                if (object instanceof PEMKeyPair) {
                    JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
                    PEMKeyPair pemKeyPair = (PEMKeyPair) object;
                    return converter.getKeyPair(pemKeyPair);
                }
            }
        }
        throw new RuntimeException("Can't read certificate key pair from file: " + keyPairFilePath);
    }

    protected void writePrivateKeyPEM(KeyPair keyPair, File file) throws IOException {
        writeBCObject(keyPair.getPrivate(), file);
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file.toPath(), perms);
    }

    protected void writePrivateKeyDER(KeyPair keyPair, File file) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(keyPair.getPrivate().getEncoded());
        }
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file.toPath(), perms);
    }

    protected void writeCertificate(X509Certificate certificate, File file) throws IOException {
        writeBCObject(certificate, file);
    }

    private <T> void writeBCObject(T object, File file) throws IOException {
        FileWriter fileWriter = new FileWriter(file);
        JcaPEMWriter pemWriter = new JcaPEMWriter(fileWriter);
        pemWriter.writeObject(object);
        pemWriter.close();
    }

}
