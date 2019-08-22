package no.neic.localega.deploy.pgp;

import no.neic.localega.deploy.LocalEGATask;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.sig.Features;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.gradle.api.tasks.TaskAction;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public class GeneratePGPKeyTask extends LocalEGATask {

    @TaskAction
    public void run() throws Exception {
        String id = getProperty("id");
        String passphrase = getProperty("passphrase");
        generatePGPKeyPair(id, passphrase);
        File egaSecPass = getProject().file(id + ".sec.pass");
        FileUtils.write(egaSecPass, passphrase, Charset.defaultCharset());
    }


    private void generatePGPKeyPair(String id, String passphrase) throws Exception {
        PGPKeyRingGenerator generator = createPGPKeyRingGenerator(id, passphrase.toCharArray());

        PGPPublicKeyRing pgpPublicKeyRing = generator.generatePublicKeyRing();
        ByteArrayOutputStream pubOut = new ByteArrayOutputStream();
        pgpPublicKeyRing.encode(pubOut);
        pubOut.close();

        PGPSecretKeyRing pgpSecretKeyRing = generator.generateSecretKeyRing();
        ByteArrayOutputStream secOut = new ByteArrayOutputStream();
        pgpSecretKeyRing.encode(secOut);
        secOut.close();

        byte[] armoredPublicBytes = armorByteArray(pubOut.toByteArray());
        byte[] armoredSecretBytes = armorByteArray(secOut.toByteArray());

        File pubFile = getProject().file(id + ".pub");
        FileUtils.write(pubFile, new String(armoredPublicBytes), Charset.defaultCharset());

        File secFile = getProject().file(id + ".sec");
        FileUtils.write(secFile, new String(armoredSecretBytes), Charset.defaultCharset());
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(secFile.toPath(), perms);
    }

    private PGPKeyRingGenerator createPGPKeyRingGenerator(String id, char[] passphrase) throws Exception {
        RSAKeyPairGenerator keyPairGenerator = new RSAKeyPairGenerator();

        keyPairGenerator.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 12));

        PGPKeyPair rsaKeyPair = new BcPGPKeyPair(PGPPublicKey.RSA_GENERAL, keyPairGenerator.generateKeyPair(), new Date());

        PGPSignatureSubpacketGenerator signHashGenerator = new PGPSignatureSubpacketGenerator();
        signHashGenerator.setKeyFlags(false, KeyFlags.SIGN_DATA | KeyFlags.CERTIFY_OTHER);
        signHashGenerator.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION);

        PGPSignatureSubpacketGenerator encryptHashGenerator = new PGPSignatureSubpacketGenerator();
        encryptHashGenerator.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE);

        PGPDigestCalculator sha1DigestCalculator = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
        PGPDigestCalculator sha512DigestCalculator = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA512);

        PBESecretKeyEncryptor secretKeyEncryptor = (new BcPBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha512DigestCalculator)).build(passphrase);

        return new PGPKeyRingGenerator(PGPSignature.NO_CERTIFICATION, rsaKeyPair, id, sha1DigestCalculator, encryptHashGenerator.generate(), null,
                new BcPGPContentSignerBuilder(rsaKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA512), secretKeyEncryptor);
    }

    private byte[] armorByteArray(byte[] data) throws IOException {
        try (ByteArrayOutputStream encOut = new ByteArrayOutputStream();
             ArmoredOutputStream armorOut = new ArmoredOutputStream(encOut)) {
            armorOut.write(data);
            armorOut.flush();
            return encOut.toByteArray();
        }
    }

}
