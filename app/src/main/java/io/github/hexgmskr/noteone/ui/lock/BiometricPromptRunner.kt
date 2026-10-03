package io.github.hexgmskr.noteone.ui.lock

import android.app.Activity
import android.content.DialogInterface
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher

/**
 * 弹一次指纹/人脸验证（锁门解锁与"启用指纹解锁"两处共用）。
 *
 * 用**平台自带的 BiometricPrompt**（minSdk 36 完全够），不引 androidx.biometric——
 * CLAUDE.md 的依赖纪律；要换实现，换的就是这一个函数。
 *
 * 平台 API 的用法：`authenticate(CryptoObject(cipher), 取消信号, 执行器, 回调)`，
 * 回调里从 `AuthenticationResult.cryptoObject` 取回**同一个（此时已授权的）Cipher**。
 * 该 Cipher 来自 [BiometricUnlockActions] 的 begin* 方法——授权检查发生在
 * `doFinal`（也就是调用方拿回 Cipher 之后的 finish* 里），不是 init 时。
 *
 * 回调约定：成功给回 Cipher；失败、取消、错误一律回 `null`。
 * `onAuthenticationFailed`（单次指纹不匹配）**不回调**——那时对话框还开着，
 * 让用户继续试，提前返回会把界面上的按钮状态搞乱。
 *
 * **副标题不写"指纹或人脸"**：Keystore 的 auth-per-use 钥匙只认
 * BIOMETRIC_STRONG 级别的生物识别；小米的人脸是弱级别（防伪强度不足），
 * 在这条链路上用不了（真机实测：弹窗只收指纹）。写"指纹或人脸"是过度承诺。
 * 系统自己在弹窗里会列出可用的验证方式（"请触摸指纹传感器"），不用我们替它说。
 */
internal fun promptBiometric(
    activity: Activity,
    cipher: Cipher,
    title: String = "解锁 NoteONE",
    subtitle: String = "验证后解锁",
    negativeText: String = "用主密码",
    onResult: (Cipher?) -> Unit,
) {
    val prompt = BiometricPrompt.Builder(activity)
        .setTitle(title)
        .setSubtitle(subtitle)
        // 负按钮兼任"取消"：不设它的话 Builder 会因鉴权器组合不合法而抛异常
        .setNegativeButton(negativeText, activity.mainExecutor, DialogInterface.OnClickListener { _, _ ->
            onResult(null)
        })
        .build()

    prompt.authenticate(
        BiometricPrompt.CryptoObject(cipher),
        CancellationSignal(),
        activity.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(result.cryptoObject?.cipher)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(null)
            }

            override fun onAuthenticationFailed() {
                // 单次不匹配：对话框仍在，让用户继续试，不当作整体失败
            }
        },
    )
}
