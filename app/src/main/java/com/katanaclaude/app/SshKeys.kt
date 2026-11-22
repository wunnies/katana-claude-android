package com.katanaclaude.app

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * The phone's SSH identity. The private key lives in app-private storage (only this app
 * can read it). The public key is what you append to ~/.ssh/authorized_keys on katana.
 */
object SshKeys {
    private const val PRIV = "id_phone"
    private const val PUB = "id_phone.pub"

    fun privateKeyFile(ctx: Context) = File(ctx.filesDir, PRIV)
    private fun publicKeyFile(ctx: Context) = File(ctx.filesDir, PUB)
    fun knownHostsFile(ctx: Context): File =
        File(ctx.filesDir, "known_hosts").also { if (!it.exists()) it.createNewFile() }

    fun hasKey(ctx: Context) = privateKeyFile(ctx).exists() && publicKeyFile(ctx).exists()

    fun publicKey(ctx: Context): String? =
        publicKeyFile(ctx).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** Generates ed25519 (via BouncyCastle) and falls back to ECDSA P-256 if that is unavailable. */
    fun generate(ctx: Context, comment: String): String {
        val jsch = JSch()
        var type = "ed25519"
        val kp: KeyPair = try {
            KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        } catch (t: Throwable) {
            type = "ecdsa"
            KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
        }
        save(ctx, kp, comment)
        kp.dispose()
        return type
    }

    /** Imports an existing private key (OpenSSH or PEM), decrypting it if a passphrase is given. */
    fun import(ctx: Context, privateKeyText: String, passphrase: String?, comment: String) {
        val jsch = JSch()
        val kp = KeyPair.load(jsch, privateKeyText.trim().toByteArray(Charsets.UTF_8) + '\n'.code.toByte(), null)
        if (kp.isEncrypted) {
            if (passphrase.isNullOrEmpty()) throw IllegalArgumentException("This key is encrypted, so enter its passphrase.")
            if (!kp.decrypt(passphrase.toByteArray(Charsets.UTF_8))) throw IllegalArgumentException("Wrong passphrase for this key.")
        }
        save(ctx, kp, comment)
        kp.dispose()
    }

    private fun save(ctx: Context, kp: KeyPair, comment: String) {
        val priv = privateKeyFile(ctx)
        FileOutputStream(priv).use { kp.writeOpenSSHv1PrivateKey(it, null) }  // OpenSSH format works for every key type
        priv.setReadable(false, false); priv.setReadable(true, true)
        val pub = ByteArrayOutputStream()
        kp.writePublicKey(pub, comment)
        publicKeyFile(ctx).writeText(String(pub.toByteArray(), Charsets.UTF_8).trim() + "\n")
    }

    /** One line to paste into a Mac terminal that already has `ssh katana` working. */
    fun macInstallCommand(pub: String): String =
        "ssh katana " + Pbs.shq(remoteInstallScript(pub))

    fun remoteInstallScript(pub: String): String =
        "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
            "(grep -qxF ${Pbs.shq(pub)} ~/.ssh/authorized_keys || echo ${Pbs.shq(pub)} >> ~/.ssh/authorized_keys) && echo KEY_INSTALLED"
}
