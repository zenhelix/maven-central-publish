package test.testkit.signing

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.util.Date
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import test.testkit.signing.PgpTestUtils.PgpKeyConfig.Companion.pgpKeyConfig

/**
 * Utility class for generating PGP keys for testing purposes.
 * Primarily used for mocking PGP signatures in Gradle build tests.
 */
public object PgpTestUtils {
    public const val BOUNCY_CASTLE_PROVIDER_NAME: String = "BC"

    init {
        if (Security.getProvider(BOUNCY_CASTLE_PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Symmetric encryption algorithm options
     */
    public enum class SymmetricAlgorithm(public val id: Int) {
        AES_128(SymmetricKeyAlgorithmTags.AES_128),
        AES_192(SymmetricKeyAlgorithmTags.AES_192),
        AES_256(SymmetricKeyAlgorithmTags.AES_256),
        BLOWFISH(SymmetricKeyAlgorithmTags.BLOWFISH),
        TWOFISH(SymmetricKeyAlgorithmTags.TWOFISH)
    }

    /**
     * Configuration for PGP key generation
     */
    public class PgpKeyConfig {
        public var identity: String = "test@example.com"
        public var keySize: Int = 2048
        public var symmetricAlgorithm: SymmetricAlgorithm = SymmetricAlgorithm.AES_256

        public companion object {
            /**
             * Creates a PGP key configuration with the given initializer lambda.
             *
             * @param initializer Lambda with receiver to configure PgpKeyConfig
             * @return Configured PgpKeyConfig instance
             */
            public fun pgpKeyConfig(initializer: PgpKeyConfig.() -> Unit = {}): PgpKeyConfig =
                PgpKeyConfig().apply(initializer)
        }
    }


    /**
     * Generates an ASCII-armored PGP secret key string for testing purposes.
     *
     * Example usage:
     * ```
     * // In a Gradle test
     * val pgpKey = PgpUtils.generateArmoredPgpSecretKey("test-passphrase") {
     *     identity = "test@example.com"
     *     keySize = 4096
     * }
     *
     * signing {
     *     useInMemoryPgpKeys(pgpKey, "test-passphrase")
     *     sign(publishing.publications)
     * }
     * ```
     *
     * @param passphrase Password to protect the private key
     * @param configBuilder Optional lambda to configure key generation
     * @return ASCII-armored private key as a string
     */
    public fun generateArmoredPgpSecretKey(
        passphrase: String = "test-passphrase",
        configBuilder: PgpKeyConfig.() -> Unit = {}
    ): String {
        val config = pgpKeyConfig(configBuilder)
        val keyPair = generateRsaKeyPair(config.keySize)
        val pgpKeyPair = createPgpKeyPair(keyPair)
        val keyRingGenerator = createKeyRingGenerator(pgpKeyPair, config, passphrase)

        return ByteArrayOutputStream().use { bos ->
            ArmoredOutputStream(bos).use { aos ->
                keyRingGenerator.generateSecretKeyRing().encode(aos)
            }
            bos.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * Generates both public and private key content as strings.
     *
     * Example usage:
     * ```
     * val (privateKey, publicKey) = PgpUtils.generateArmoredKeyPair("test-passphrase") {
     *     identity = "test@example.com"
     *     keySize = 4096
     * }
     * ```
     *
     * @param passphrase Password to protect the private key
     * @param configBuilder Optional lambda to configure key generation
     * @return Pair of (privateKey, publicKey) as ASCII-armored strings
     */
    public fun generateArmoredKeyPair(
        passphrase: String = "test-passphrase",
        configBuilder: PgpKeyConfig.() -> Unit = {}
    ): Pair<String, String> {
        val config = pgpKeyConfig(configBuilder)
        val keyPair = generateRsaKeyPair(config.keySize)
        val pgpKeyPair = createPgpKeyPair(keyPair)
        val keyRingGenerator = createKeyRingGenerator(pgpKeyPair, config, passphrase)

        val secretKeyRing = keyRingGenerator.generateSecretKeyRing()
        val publicKeyRing = keyRingGenerator.generatePublicKeyRing()

        val secretKeyString = ByteArrayOutputStream().use { bos ->
            ArmoredOutputStream(bos).use { aos ->
                secretKeyRing.encode(aos)
            }
            bos.toString(Charsets.UTF_8.name())
        }

        val publicKeyString = ByteArrayOutputStream().use { bos ->
            ArmoredOutputStream(bos).use { aos ->
                publicKeyRing.encode(aos)
            }
            bos.toString(Charsets.UTF_8.name())
        }

        return Pair(secretKeyString, publicKeyString)
    }

    private fun generateRsaKeyPair(keySize: Int): KeyPair =
        KeyPairGenerator.getInstance("RSA", BOUNCY_CASTLE_PROVIDER_NAME).apply {
            initialize(keySize, SecureRandom())
        }.generateKeyPair()

    private fun createPgpKeyPair(keyPair: KeyPair): PGPKeyPair =
        JcaPGPKeyPair(PublicKeyPacket.VERSION_4, PGPPublicKey.RSA_GENERAL, keyPair, Date())

    private fun createKeyRingGenerator(
        pgpKeyPair: PGPKeyPair,
        config: PgpKeyConfig,
        passphrase: String
    ): PGPKeyRingGenerator {
        val algorithm = HashAlgorithmTags.SHA1
        val digestCalculator =
            JcaPGPDigestCalculatorProviderBuilder().setProvider(BOUNCY_CASTLE_PROVIDER_NAME).build().get(algorithm)
        val contentSignerBuilder =
            JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, algorithm).setProvider(BOUNCY_CASTLE_PROVIDER_NAME)
        val secretKeyEncryptor =
            JcePBESecretKeyEncryptorBuilder(config.symmetricAlgorithm.id).setProvider(BOUNCY_CASTLE_PROVIDER_NAME)
                .build(passphrase.toCharArray())

        return PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pgpKeyPair,
            config.identity,
            digestCalculator,
            null,
            null,
            contentSignerBuilder,
            secretKeyEncryptor
        )
    }
}