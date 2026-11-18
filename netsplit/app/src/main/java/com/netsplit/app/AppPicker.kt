package com.netsplit.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

/** Searchable list of installed apps. */
object AppPicker {
    class App(val pkg: String, val label: String, val icon: Drawable)

    fun show(act: Activity, title: String, onPick: (App) -> Unit) {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics).toInt() }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val search = EditText(act).apply { hint = "Search apps"; isSingleLine = true }
        val progress = ProgressBar(act)
        val list = ListView(act).apply { visibility = View.GONE }
        root.addView(search)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))

        val dialog = AlertDialog.Builder(act).setTitle(title).setView(root)
            .setNegativeButton("Cancel", null).create()

        var all = emptyList<App>()
        var shown = emptyList<App>()
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
                val row = (convert as? LinearLayout) ?: LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    addView(ImageView(act), LinearLayout.LayoutParams(dp(36), dp(36)))
                    addView(LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, 0)
                        addView(TextView(act).apply { textSize = 16f })
                        addView(TextView(act).apply { textSize = 11f; alpha = 0.6f })
                    })
                }
                val app = shown[i]
                (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                val texts = row.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = app.label
                (texts.getChildAt(1) as TextView).text = app.pkg
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ -> onPick(shown[i]); dialog.dismiss() }
        fun filter() {
            val q = search.text.toString().trim().lowercase()
            shown = if (q.isEmpty()) all else all.filter { q in it.label.lowercase() || q in it.pkg }
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })
        dialog.show()

        thread {
            val pm = act.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != act.packageName }
                .map { App(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .sortedBy { it.label.lowercase() }
            act.runOnUiThread {
                all = apps
                progress.visibility = View.GONE
                list.visibility = View.VISIBLE
                filter()
            }
        }
    }
}
