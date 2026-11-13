package com.zeldaromtool

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Pop-up AMOLED (fundo preto puro) para confirmar o cancelamento da extração.
 *
 * - A extração CONTINUA rodando enquanto o pop-up está aberto.
 * - A barra de progresso no topo atualiza ao vivo.
 * - Voltar / tocar fora = "Continuar Extraindo" (nada é cancelado por acidente).
 * - O botão "Cancelar Extração" fica bloqueado por ~1,5 s para evitar toque duplo.
 *
 * Uso no botão Cancelar (ExeFS / RomFS da atualização):
 *
 * btnCancelar.setOnClickListener {
 *     CancelConfirmDialog.show(
 *         activity = this,
 *         progress = { progressoEmMilesimos },   // 0..1000 (ex.: bytesFeitos * 1000 / bytesTotal)
 *         onConfirmCancel = { cancelarExtracao() } // aqui sim cancela de verdade
 *     )
 * }
 */
object CancelConfirmDialog {

    private const val BLACK = 0xFF000000.toInt()
    private const val GREEN = 0xFF4ADE80.toInt()
    private const val GRAY = 0xFF2A2A2A.toInt()
    private const val RED = 0xFFFF5252.toInt()

    fun show(
        activity: Activity,
        progress: () -> Int,
        onConfirmCancel: () -> Unit
    ): Dialog {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Barra de progresso (fica acima de tudo)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(GREEN)
            progressBackgroundTintList = ColorStateList.valueOf(GRAY)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(8)
            )
        }
        val percent = TextView(activity).apply {
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 13f
            gravity = Gravity.END
            setPadding(0, px(6), 0, px(16))
        }
        val title = TextView(activity).apply {
            text = "Confirmar o cancelamento?"
            setTextColor(Color.WHITE)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, px(4), 0, px(20))
        }
        val btnContinue = Button(activity).apply {
            text = "Continuar Extraindo"
            isAllCaps = false
            setTextColor(BLACK)
            background = GradientDrawable().apply {
                setColor(GREEN); cornerRadius = px(14).toFloat()
            }
        }
        val btnCancel = Button(activity).apply {
            text = "Cancelar Extração"
            isAllCaps = false
            setTextColor(RED)
            isEnabled = false
            alpha = 0.4f
            background = GradientDrawable().apply {
                setColor(BLACK); setStroke(px(1), RED); cornerRadius = px(14).toFloat()
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(20))
            background = GradientDrawable().apply {
                setColor(BLACK)
                setStroke(px(1), GRAY)   // contorno fino para o pop-up aparecer em fundo preto
                cornerRadius = px(22).toFloat()
            }
            addView(bar)
            addView(percent)
            addView(title)
            addView(btnContinue, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(52)))
            addView(btnCancel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(52)).apply { topMargin = px(10) })
        }

        // Atualização ao vivo da barra
        val handler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                val p = progress().coerceIn(0, 1000)
                bar.progress = p
                percent.text = String.format("%.1f%%", p / 10.0)
                handler.postDelayed(this, 500)
            }
        }

        btnContinue.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener {
            dialog.dismiss()
            onConfirmCancel()
        }
        handler.postDelayed({
            btnCancel.isEnabled = true
            btnCancel.alpha = 1f
        }, 1500)

        dialog.setContentView(root)
        dialog.setCancelable(true)          // voltar / tocar fora = continuar extraindo
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.9).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        dialog.show()
        ticker.run()
        return dialog
    }
}
