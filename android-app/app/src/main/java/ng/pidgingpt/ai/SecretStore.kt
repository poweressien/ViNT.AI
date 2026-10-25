package ng.pidgingpt.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's own online key/token encrypted with an Android Keystore key (AES-256-GCM), in app-private storage.
 * This protects it at rest on this phone. It does NOT make a key safe to ship inside an APK: nothing in the source or repository holds a key.
 * Never logged, never shown after saving, never sent anywhere except as the Authorization header of the configured HTTPS endpoint.
 */
class SecretStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "vint_online_secret"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return kg.generateKey()
    }

    val hasSecret: Boolean get() = sp.contains("v")

    fun put(value: String?) {
        if (value.isNullOrBlank()) { sp.edit().remove("v").apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        sp.edit().putString("v", Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
    }

    fun get(): String? = runCatching {
        val s = sp.getString("v", null) ?: return null
        val (iv, ct) = s.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
}
