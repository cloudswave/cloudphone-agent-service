package com.cloudphone.agentservice

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Execute shell commands via su (root).
 */
object RootHelper {
    private const val TAG = "RootHelper"

    fun hasRoot(): Boolean {
        return try {
            exec("id").first == 0
        } catch (e: Exception) {
            false
        }
    }

    fun exec(command: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            Pair(exitCode, "$stdout$stderr".trim())
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            Pair(-1, e.message ?: "error")
        }
    }

    fun execBackground(command: String): Boolean {
        val (exit, _) = exec("nohup sh -c '$command' > /data/local/tmp/cloudphone-agent.log 2>&1 &")
        return exit == 0
    }

    fun readConfigFromSdcard(): String? {
        val (exit, output) = exec("cat /sdcard/cloudphone/agent.config 2>/dev/null")
        return if (exit == 0 && output.isNotBlank()) output else null
    }

    fun deployAgent(srcPath: String, destPath: String): Boolean {
        val (exit, _) = exec("cp '$srcPath' '$destPath' && chmod 755 '$destPath'")
        return exit == 0
    }

    fun deployJar(srcPath: String, destPath: String): Boolean {
        val (exit, _) = exec("cp '$srcPath' '$destPath' && chmod 644 '$destPath'")
        return exit == 0
    }

    fun stopAgent() {
        exec("pkill -f '/data/local/tmp/cloudphone-agent' 2>/dev/null || true")
        exec("pkill -f 'scrcpy.Server' 2>/dev/null || true")
    }

    fun isAgentRunning(): Boolean {
        val (exit, _) = exec("pidof /data/local/tmp/cloudphone-agent 2>/dev/null || true")
        return exit == 0
    }

    fun startAgent(agentId: String, signaling: String, jarPath: String, iceServers: String = ""): Boolean {
        val resolvedSignaling = if (signaling.contains("://")) signaling else "ws://$signaling"
        val cmd = buildString {
            append("/data/local/tmp/cloudphone-agent -id $agentId -signaling $resolvedSignaling -jar $jarPath")
            if (iceServers.isNotBlank()) {
                append(" -ice-servers \"$iceServers\"")
            }
        }
        Log.i(TAG, "Starting agent: $cmd")
        return execBackground(cmd)
    }
}