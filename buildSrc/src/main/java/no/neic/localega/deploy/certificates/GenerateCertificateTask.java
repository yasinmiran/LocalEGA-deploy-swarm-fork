package no.neic.localega.deploy.certificates;

import no.neic.localega.deploy.LocalEGATask;
import org.apache.commons.lang3.StringUtils;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.bc.BcX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.gradle.api.tasks.TaskAction;
import org.joda.time.DateTime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

public class GenerateCertificateTask extends LocalEGATask {

    // Subject string example:
    // C=NO,ST=Oslo,L=Oslo,O=UiO,OU=IFI,CN=nels-developers@googlegroups.com
    @TaskAction
    public void run() throws Exception {
        CertificateType type = StringUtils.isEmpty(getProperty("type")) ? CertificateType.ROOT : CertificateType.valueOf(getProperty("type"));
        String subjectString = getProperty("subjectString");
        if (type == CertificateType.ROOT) {
            generateRootCA(subjectString);
        } else {
            X509Certificate rootCA = readX509Certificate(getProperty("rootCA"));
            KeyPair rootCAKey = readKeyPair(getProperty("rootCAKey"));
            generateCertificate(subjectString, type,
                    getProperty("dnsName"), getProperty("ipAddress"),
                    rootCA, rootCAKey,
                    getProperty("jksPassword"),
                    getProperty("fileName"));
        }
    }

    private void generateRootCA(String subjectString) throws IOException, GeneralSecurityException, OperatorCreationException {
        KeyPair keyPair = KeyUtils.generateKeyPair("ssh-rsa", 2048);

        RDN[] rdns = BCStyle.INSTANCE.fromString(subjectString);
        X500NameBuilder x500NameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        for (RDN rdn : rdns) {
            x500NameBuilder.addRDN(rdn.getFirst().getType(), rdn.getFirst().getValue());
        }
        X500Name subject = x500NameBuilder.build();

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, getSecureRandomSerial(),
                DateTime.now().minusDays(1).toDate(),
                DateTime.now().plusYears(1).toDate(),
                subject, keyPair.getPublic());

        builder.addExtension(Extension.subjectKeyIdentifier, false, getSubjectKeyId(keyPair.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false, getAuthorityKeyId(keyPair.getPublic()));
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(
                KeyUsage.keyCertSign | KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment | KeyUsage.cRLSign
        ));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);

        JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
        converter.setProvider(new BouncyCastleProvider());
        X509Certificate certificate = converter.getCertificate(holder);

        writeCertificate(certificate, getProject().file("rootCA.pem"));
        writePrivateKeyPEM(keyPair, getProject().file("rootCA-key.pem"));
        writePrivateKeyDER(keyPair, getProject().file("rootCA-key.der"));
    }

    private void generateCertificate(String subjectString,
                                     CertificateType type,
                                     String dnsName,
                                     String ipAddress,
                                     X509Certificate rootCA,
                                     KeyPair rootKeyPair,
                                     String jksPassword,
                                     String name) throws Exception {
        KeyPair keyPair = KeyUtils.generateKeyPair("ssh-rsa", 2048);

        RDN[] rdns = BCStyle.INSTANCE.fromString(subjectString);
        X500NameBuilder x500NameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        for (RDN rdn : rdns) {
            x500NameBuilder.addRDN(rdn.getFirst().getType(), rdn.getFirst().getValue());
        }
        X500Name subject = x500NameBuilder.build();

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(rootCA, getSecureRandomSerial(),
                DateTime.now().minusDays(1).toDate(),
                DateTime.now().plusYears(1).toDate(),
                subject, keyPair.getPublic());

        builder.addExtension(Extension.subjectKeyIdentifier, true, getSubjectKeyId(keyPair.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, true, getAuthorityKeyId(rootKeyPair.getPublic()));
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(
                KeyUsage.nonRepudiation | KeyUsage.digitalSignature | KeyUsage.keyEncipherment
        ));
        KeyPurposeId[] usages;
        switch (type) {
            case CLIENT:
                usages = new KeyPurposeId[]{KeyPurposeId.id_kp_clientAuth};
                break;
            case SERVER:
                usages = new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth};
                break;
            default:
                usages = new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth};
                break;
        }
        builder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(usages));

        if (type != CertificateType.CLIENT) {
            if (StringUtils.isEmpty(dnsName) && StringUtils.isEmpty(ipAddress)) {
                throw new IllegalArgumentException("Server certificate must have either DNS name or IP address argument specified");
            }
            List<GeneralName> generalNameList = new ArrayList<>();
            if (StringUtils.isNotEmpty(dnsName)) {
                generalNameList.add(new GeneralName(GeneralName.dNSName, ipAddress));
            }
            if (StringUtils.isNotEmpty(ipAddress)) {
                generalNameList.add(new GeneralName(GeneralName.iPAddress, ipAddress));
            }
            GeneralNames generalNames = GeneralNames.getInstance(new DERSequence(generalNameList.toArray(new GeneralName[]{})));
            builder.addExtension(Extension.subjectAlternativeName, false, generalNames);
        }

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);

        JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
        converter.setProvider(new BouncyCastleProvider());
        X509Certificate certificate = converter.getCertificate(holder);

        writeCertificate(certificate, getProject().file(name + ".pem"));
        writePrivateKeyPEM(keyPair, getProject().file(name + "-key.pem"));
        writePrivateKeyDER(keyPair, getProject().file(name + "-key.der"));

        if (StringUtils.isNotEmpty(jksPassword)) {
            saveAsKeyStore(rootCA, certificate, keyPair, jksPassword, name);
        }
    }

    private void saveAsKeyStore(X509Certificate rootCA, X509Certificate certificate, KeyPair keyPair, String keyStorePassword, String name) throws Exception {
        Certificate[] chain = new Certificate[]{certificate, rootCA};
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry(rootCA.getSubjectX500Principal().getName(), rootCA);
        keyStore.setKeyEntry(certificate.getSubjectX500Principal().getName(), keyPair.getPrivate(), keyStorePassword.toCharArray(), chain);
        File file = getProject().file(name + ".jks");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            keyStore.store(fos, keyStorePassword.toCharArray());
        }
    }

    private BigInteger getSecureRandomSerial() {
        SecureRandom random = new SecureRandom();
        byte[] id = new byte[20];
        random.nextBytes(id);
        return new BigInteger(160, random);
    }

    private AuthorityKeyIdentifier getAuthorityKeyId(PublicKey pub) {
        SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(pub.getEncoded());
        return new BcX509ExtensionUtils().createAuthorityKeyIdentifier(info);
    }

    private SubjectKeyIdentifier getSubjectKeyId(PublicKey pub) {
        SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(pub.getEncoded());
        return new BcX509ExtensionUtils().createSubjectKeyIdentifier(info);
    }

}
