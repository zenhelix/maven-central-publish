package test.utils

import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Date
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder

internal object PgpUtils {
    fun generatePgpKeyPair(passphrase: String): String {
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
        keyPairGenerator.initialize(2048)

        val rsaKeyPair = keyPairGenerator.generateKeyPair()
        val secretKeyEncryptor = JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
            .setProvider("BC")
            .build(passphrase.toCharArray())

        val publicKey = JcaPGPKeyPair(PublicKeyPacket.VERSION_4, PGPPublicKey.RSA_GENERAL, rsaKeyPair, Date())
        val digestCalculator = JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1)
        val contentSignerBuilder = JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA1)
        val keyRingBuilder = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            publicKey,
            "test@example.com",
            digestCalculator,
            null,
            null,
            contentSignerBuilder,
            secretKeyEncryptor
        )

        return ByteArrayOutputStream().use {
            ArmoredOutputStream(it).use { keyRingBuilder.generateSecretKeyRing().encode(it) }
            it.toString(Charsets.UTF_8)
        }
    }
}