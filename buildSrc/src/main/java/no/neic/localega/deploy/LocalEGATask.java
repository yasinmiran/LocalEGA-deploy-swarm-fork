package no.neic.localega.deploy;

import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.common.Base64;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.gradle.api.DefaultTask;

import java.io.*;
import java.nio.charset.Charset;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

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

    protected KeyPair readKeyPair(String keyPairFilePath) throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
        String keyFileContent = FileUtils.readFileToString(new File(keyPairFilePath), Charset.defaultCharset());
        if (keyFileContent.startsWith("-----BEGIN RSA PRIVATE KEY-----")) {
            return readPKCS1KeyPair(keyPairFilePath);
        } else {
            return readPKCS8KeyPair(keyFileContent);
        }
    }

    private KeyPair readPKCS1KeyPair(String keyPairFilePath) throws IOException {
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

    private KeyPair readPKCS8KeyPair(String keyPairFileContent) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        keyPairFileContent = keyPairFileContent
                .replaceAll("\\n", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "");
        byte[] encoded = Base64.decode(keyPairFileContent);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(encoded);
        PrivateKey privateKey = keyFactory.generatePrivate(pkcs8EncodedKeySpec);
        RSAPublicKeySpec rsaPublicKeySpec = getRSAPublicKeySpec(privateKey);
        PublicKey publicKey = keyFactory.generatePublic(rsaPublicKeySpec);
        return new KeyPair(publicKey, privateKey);
    }

    private RSAPublicKeySpec getRSAPublicKeySpec(PrivateKey privateKey) {
        RSAPrivateCrtKey rsaPrivateCrtKey = (RSAPrivateCrtKey) privateKey;
        return new RSAPublicKeySpec(rsaPrivateCrtKey.getModulus(), rsaPrivateCrtKey.getPublicExponent());
    }

    protected void writePrivateKeyPEM(KeyPair keyPair, File file) throws IOException {
        writeBCObject(keyPair.getPrivate(), file);
    }

    protected void writePrivateKeyDER(KeyPair keyPair, File file) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(keyPair.getPrivate().getEncoded());
        }
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
