import com.jcraft.jsch.*
import java.io.*

// Same logic as SshKeys.import() + save()
fun importKey(text: String, pass: String?, outPriv: File): String {
    val jsch = JSch()
    val kp = KeyPair.load(jsch, text.trim().toByteArray(Charsets.UTF_8) + '\n'.code.toByte(), null)
    if (kp.isEncrypted) {
        if (pass.isNullOrEmpty()) throw IllegalArgumentException("This key is encrypted, so enter its passphrase.")
        if (!kp.decrypt(pass.toByteArray(Charsets.UTF_8))) throw IllegalArgumentException("Wrong passphrase for this key.")
    }
    FileOutputStream(outPriv).use { kp.writeOpenSSHv1PrivateKey(it, null) }
    val b = ByteArrayOutputStream(); kp.writePublicKey(b, "phone"); return String(b.toByteArray()).trim()
}

fun main() {
    var fails = 0
    for ((f, pw) in listOf("k_ed" to "secret pass", "k_rsa" to null, "k_rsapem" to "pw2pw2", "k_ec" to null)) {
        try {
            val out = File("/tmp/imp/$f.imported")
            val pub = importKey(File("/tmp/imp/$f").readText(), pw, out)
            File("/home/z5019209/.ssh/authorized_keys").appendText(pub + "\n")
            val j = JSch(); j.addIdentity(out.path)
            val s = j.getSession("z5019209", "127.0.0.1", 2022); s.setConfig("StrictHostKeyChecking", "no"); s.connect(10000)
            println("  PASS import+login $f (${pub.substringBefore(' ')})"); s.disconnect()
        } catch (e: Exception) { println("  FAIL $f: $e"); fails++ }
    }
    try { importKey(File("/tmp/imp/k_ed").readText(), "wrong", File("/tmp/imp/x")); println("  FAIL wrong passphrase accepted"); fails++ } catch (e: IllegalArgumentException) { println("  PASS wrong passphrase rejected (${e.message})") }
    try { importKey(File("/tmp/imp/k_ed").readText(), null, File("/tmp/imp/x")); println("  FAIL missing passphrase accepted"); fails++ } catch (e: IllegalArgumentException) { println("  PASS missing passphrase rejected (${e.message})") }
    println(if (fails == 0) "IMPORT OK" else "$fails FAILED")
}
