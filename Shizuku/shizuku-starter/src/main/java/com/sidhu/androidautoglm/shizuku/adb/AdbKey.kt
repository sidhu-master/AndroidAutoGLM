package com.sidhu.androidautoglm.shizuku.adb

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

private const val TAG = "AdbKey"

class AdbKey(private val adbKeyStore: AdbKeyStore, private val name: String) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_adbkey_encryption_key_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BYTES = 16

        private val PADDING = byteArrayOf(
            0x00, 0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00,
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
            0x04, 0x14
        )
    }

    private val encryptionKey: Key = getOrCreateEncryptionKey()
        ?: error("Failed to generate encryption key with AndroidKeyManager.")

    private val privateKey: RSAPrivateKey = getOrCreatePrivateKey()
    private val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey
    private val certificate: X509Certificate = run {
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val x509 = X509v3CertificateBuilder(
            X500Name("CN=00"), BigInteger.ONE, Date(0), Date(2461449600 * 1000),
            Locale.ROOT, X500Name("CN=00"), SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        ).build(signer)
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(x509.encoded)) as X509Certificate
    }

    val adbPublicKey: ByteArray by lazy { publicKey.adbEncoded(name) }

    @get:RequiresApi(Build.VERSION_CODES.R)
    val sslContext: SSLContext by lazy {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        ctx
    }

    private fun getOrCreateEncryptionKey(): Key? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.getKey(ENCRYPTION_KEY_ALIAS, null) ?: run {
            val spec = KeyGenParameterSpec.Builder(ENCRYPTION_KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
        }
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray?): ByteArray? {
        if (plaintext.size > Int.MAX_VALUE - IV_SIZE_IN_BYTES - TAG_SIZE_IN_BYTES) return null
        val ciphertext = ByteArray(IV_SIZE_IN_BYTES + plaintext.size + TAG_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IV_SIZE_IN_BYTES)
        System.arraycopy(cipher.iv, 0, ciphertext, 0, IV_SIZE_IN_BYTES)
        return ciphertext
    }

    private fun decrypt(ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        if (ciphertext.size < IV_SIZE_IN_BYTES + TAG_SIZE_IN_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(8 * TAG_SIZE_IN_BYTES, ciphertext, 0, IV_SIZE_IN_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_SIZE_IN_BYTES, ciphertext.size - IV_SIZE_IN_BYTES)
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        val aad = ByteArray(16).apply { "adbkey".toByteArray().copyInto(this) }
        var pk: RSAPrivateKey? = adbKeyStore.get()?.let { ct ->
            try {
                decrypt(ct, aad)?.let { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(it)) as RSAPrivateKey }
            } catch (_: Exception) { null }
        }
        if (pk == null) {
            pk = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
                .apply { initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4)) }
                .generateKeyPair().private as RSAPrivateKey
            encrypt(pk.encoded, aad)?.let { adbKeyStore.put(it) }
        }
        return pk
    }

    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    private val keyManager: X509ExtendedKeyManager get() = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?): String? =
            if (keyTypes.any { it == "RSA" }) "key" else null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == "key") arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "key") privateKey else null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    @get:RequiresApi(Build.VERSION_CODES.R)
    private val trustManager: X509ExtendedTrustManager get() = object : X509ExtendedTrustManager() {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }
}

interface AdbKeyStore {
    fun put(bytes: ByteArray)
    fun get(): ByteArray?
}

class PreferenceAdbKeyStore(private val preference: SharedPreferences) : AdbKeyStore {
    override fun put(bytes: ByteArray) {
        preference.edit { putString("adbkey", String(Base64.encode(bytes, Base64.NO_WRAP))) }
    }
    override fun get(): ByteArray? =
        preference.getString("adbkey", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
}

private const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
private const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
private const val RSAPublicKey_Size = 524

private fun BigInteger.toAdbEncoded(): IntArray {
    val encoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)
    var tmp = this
    for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val (q, r) = tmp.divideAndRemainder(r32)
        tmp = q
        encoded[i] = r.toInt()
    }
    return encoded
}

private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)
    val buffer = ByteBuffer.allocate(RSAPublicKey_Size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())
    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    return ByteArray(base64Bytes.size + nameBytes.size).apply {
        base64Bytes.copyInto(this)
        nameBytes.copyInto(this, base64Bytes.size)
    }
}
