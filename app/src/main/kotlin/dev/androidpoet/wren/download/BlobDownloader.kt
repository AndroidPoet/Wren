/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

import android.app.Application
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.androidpoet.wren.core.data.repository.DownloadRepository
import dev.androidpoet.wren.core.model.Download
import dev.androidpoet.wren.core.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads for blob: URLs, which exist only inside the page's JavaScript heap - the system
 * DownloadManager has nothing it could fetch. The page reads its own blob in slices and streams
 * them across the JS bridge as base64; this side decodes into a temp file and then publishes it
 * into the device's Downloads, recorded in the same list as every other download.
 */
@Singleton
class BlobDownloader @Inject constructor(
  private val application: Application,
  private val repository: DownloadRepository,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private class Transfer(val file: File, val stream: OutputStream, var bytes: Long = 0L)

  /** Only transfers this side started are accepted; unknown ids from page JS are ignored. */
  private val transfers = ConcurrentHashMap<String, Transfer>()

  private val isSupported: Boolean
    get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

  /**
   * An origin-scoped message channel rather than a JavaScript interface: the page can only post
   * strings to it, nothing on this side is callable by name, and a transfer id it did not
   * receive from us is ignored.
   */
  fun install(webView: WebView) {
    if (!isSupported) return
    WebViewCompat.addWebMessageListener(
      webView,
      BRIDGE_NAME,
      ALL_ORIGINS,
    ) { _, message, _, _, _ ->
      if (message.type == WebMessageCompat.TYPE_STRING) message.data?.let(::onMessage)
    }
  }

  private fun onMessage(payload: String) {
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
    val id = json.optString("id")
    when (json.optString("type")) {
      "chunk" -> onChunk(id, json.optString("data"))
      "done" -> onDone(id, json.optString("mime"))
      "error" -> abort(id, json.optString("reason"))
    }
  }

  fun download(webView: WebView, blobUrl: String, mimeType: String?) {
    if (!isSupported) {
      Timber.w("blob downloads need a WebView with message listeners")
      return
    }
    val id = UUID.randomUUID().toString()
    val file = File(application.cacheDir, "blob-" + id)
    transfers[id] = Transfer(file, file.outputStream().buffered())
    val script = READER_SCRIPT
      .replace("__ID__", id)
      // Both strings come from the page; quoting them as JSON is what keeps them strings.
      .replace("__URL__", JSONObject.quote(blobUrl))
      .replace("__MIME__", JSONObject.quote(mimeType.orEmpty()))
    webView.evaluateJavascript(script, null)
  }

  private fun onChunk(id: String, base64: String) {
    val transfer = transfers[id] ?: return
    runCatching {
      val bytes = Base64.decode(base64, Base64.DEFAULT)
      transfer.stream.write(bytes)
      transfer.bytes += bytes.size
      if (transfer.bytes > MAX_BYTES) error("blob too large")
    }.onFailure { abort(id, it.message.orEmpty()) }
  }

  private fun onDone(id: String, mimeType: String) {
    val transfer = transfers.remove(id) ?: return
    scope.launch {
      runCatching {
        transfer.stream.close()
        val mime = mimeType.ifBlank { "application/octet-stream" }.substringBefore(';')
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val name = "download-" + System.currentTimeMillis() + "." + extension
        val uri = publish(transfer.file, name, mime) ?: error("could not publish")
        repository.record(
          Download(
            // Negative, so it can never collide with a DownloadManager id.
            id = -System.currentTimeMillis(),
            url = "blob:",
            fileName = name,
            mimeType = mime,
            totalBytes = transfer.bytes,
            status = DownloadStatus.COMPLETE,
            localUri = uri,
            createdAt = System.currentTimeMillis(),
          ),
        )
        Timber.d("blob saved: %s (%d bytes)", name, transfer.bytes)
      }.onFailure { Timber.w(it, "blob download failed") }
      transfer.file.delete()
    }
  }

  private fun abort(id: String, reason: String) {
    val transfer = transfers.remove(id) ?: return
    runCatching { transfer.stream.close() }
    transfer.file.delete()
    Timber.w("blob transfer aborted: %s", reason)
  }

  private fun publish(source: File, displayName: String, mimeType: String): String? =
    DownloadsFolder.publish(application, source, displayName, mimeType)

  private companion object {
    const val BRIDGE_NAME = "WrenBlobSink"
    val ALL_ORIGINS = setOf("*")
    const val MAX_BYTES = 512L * 1024 * 1024

    val READER_SCRIPT = """
      (function () {
        var id = '__ID__';
        function post(m) { m.id = id; WrenBlobSink.postMessage(JSON.stringify(m)); }
        try {
          fetch(__URL__).then(function (r) { return r.blob(); }).then(function (blob) {
            var CHUNK = 512 * 1024;
            var offset = 0;
            function next() {
              if (offset >= blob.size) {
                post({ type: 'done', mime: blob.type || __MIME__ });
                return;
              }
              var reader = new FileReader();
              reader.onloadend = function () {
                var data = reader.result || '';
                post({ type: 'chunk', data: data.substring(data.indexOf(',') + 1) });
                offset += CHUNK;
                next();
              };
              reader.onerror = function () { post({ type: 'error', reason: 'read' }); };
              reader.readAsDataURL(blob.slice(offset, offset + CHUNK));
            }
            next();
          }).catch(function (e) { post({ type: 'error', reason: String(e) }); });
        } catch (e) {
          post({ type: 'error', reason: String(e) });
        }
      })();
    """
  }
}
