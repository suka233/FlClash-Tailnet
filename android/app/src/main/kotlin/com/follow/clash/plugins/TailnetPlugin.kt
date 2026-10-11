package com.follow.clash.plugins

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import com.follow.clash.tailnet.TailnetRuntime
import com.google.gson.Gson
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.util.concurrent.Executors

/** The system document picker grants access only to the file chosen by the user. */
class TailnetPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private val worker = Executors.newSingleThreadExecutor()
    private var binding: ActivityPluginBinding? = null
    private var pending: MethodChannel.Result? = null
    private var peer: String? = null
    private var incoming: String? = null
    private val listener = PluginRegistry.ActivityResultListener(::onActivityResult)

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "flclash/tailnet")
        channel.setMethodCallHandler(this)
        if (TailnetRuntime.enabled(context)) TailnetRuntime.setEnabled(context, true)
    }

    override fun onMethodCall(call: MethodCall, raw: MethodChannel.Result) {
        val result = MainThreadResult(raw)
        when (call.method) {
            "status" -> reply(result) { Gson().toJson(TailnetRuntime.snapshot(context)) }
            "enabled" -> {
                TailnetRuntime.setEnabled(context, call.arguments == true)
                result.success(null)
            }
            "login" -> reply(result) { TailnetRuntime.login(context, call.arguments as String) }
            "send" -> pick(result, call.arguments as String, null)
            "save" -> pick(result, null, call.arguments as String)
            else -> result.notImplemented()
        }
    }

    private fun reply(result: MethodChannel.Result, operation: () -> Any?) {
        worker.execute {
            runCatching(operation).onSuccess(result::success)
                .onFailure { result.error("TAILNET", it.message, null) }
        }
    }

    private fun pick(result: MethodChannel.Result, target: String?, name: String?) {
        val activity = binding?.activity
        if (pending != null || activity == null) {
            result.error("TAILNET_BUSY", "Document picker is unavailable or already open", null)
            return
        }
        pending = result
        peer = target
        incoming = name
        val intent = if (target != null) Intent(Intent.ACTION_OPEN_DOCUMENT)
            else Intent(Intent.ACTION_CREATE_DOCUMENT).putExtra(Intent.EXTRA_TITLE, name)
        intent.addCategory(Intent.CATEGORY_OPENABLE).type = "*/*"
        runCatching { activity.startActivityForResult(intent, 7401) }.onFailure {
            pending = null
            result.error("TAILNET_PICKER", it.message, null)
        }
    }

    private fun onActivityResult(code: Int, resultCode: Int, data: Intent?): Boolean {
        if (code != 7401) return false
        val result = pending ?: return true
        pending = null
        val target = peer
        val name = incoming
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) { result.success(false); return true }
        reply(result) {
            if (target != null) {
                val file = File.createTempFile("taildrop-", ".upload", context.cacheDir)
                try {
                    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "taildrop-file"
                    context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use(input::copyTo) }
                    TailnetRuntime.send(context, target, file, displayName)
                } finally { file.delete() }
            } else {
                // Keep the received file in the inbox until the user explicitly removes it.
                context.contentResolver.openOutputStream(uri, "w")!!.use { TailnetRuntime.receive(context, name!!, it) }
            }
            true
        }
        return true
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addActivityResultListener(listener)
    }
    override fun onDetachedFromActivityForConfigChanges() {
        binding?.removeActivityResultListener(listener); binding = null
    }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
    override fun onDetachedFromActivity() {
        onDetachedFromActivityForConfigChanges()
        pending?.error("TAILNET_PICKER", "Activity closed", null); pending = null
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        worker.shutdown()
    }
}
