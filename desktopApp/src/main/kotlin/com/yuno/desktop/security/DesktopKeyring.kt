package com.yuno.desktop.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object DesktopKeyring {
    private const val APPLICATION = "yuno"
    private val windows get() = System.getProperty("os.name").startsWith("Windows", true)
    private val linux get() = System.getProperty("os.name").startsWith("Linux", true)

    fun isAvailable(): Boolean = when {
        windows -> true
        linux -> executableOnPath("secret-tool")
        else -> false
    }

    fun get(key: String): String? = when {
        windows -> {
            val encoded = runPowerShell("read", key, "")
            if (encoded.isBlank()) null else {
                val compressed = Base64.getDecoder().decode(encoded)
                GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes().toString(UTF_8) }
            }
        }
        linux -> runSecretTool(listOf("lookup", "application", APPLICATION, "key", key), null)
            .takeIf { it.isNotEmpty() }
        else -> throw UnsupportedOperationException("No supported system keyring is available on this platform.")
    }

    fun put(key: String, value: String) {
        when {
            windows -> {
                val output = ByteArrayOutputStream().also { buffer ->
                    GZIPOutputStream(buffer).use { it.write(value.toByteArray(UTF_8)) }
                }
                val encoded = Base64.getEncoder().encodeToString(output.toByteArray())
                runPowerShell("write", key, encoded)
            }
            linux -> runSecretTool(listOf("store", "--label=Yuno", "application", APPLICATION, "key", key), value)
            else -> throw UnsupportedOperationException("No supported system keyring is available on this platform.")
        }
    }

    fun delete(key: String) {
        when {
            windows -> runPowerShell("delete", key, "")
            linux -> runSecretTool(listOf("clear", "application", APPLICATION, "key", key), null)
            else -> throw UnsupportedOperationException("No supported system keyring is available on this platform.")
        }
    }

    private fun executableOnPath(name: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { File(it, name).let { file -> file.isFile && file.canExecute() } }

    private fun runSecretTool(arguments: List<String>, secret: String?): String {
        val process = ProcessBuilder(listOf("secret-tool") + arguments).start()
        if (secret != null) {
            process.outputStream.bufferedWriter(UTF_8).use {
                it.write(secret)
                it.newLine()
            }
        } else {
            process.outputStream.close()
        }
        val output = process.inputStream.bufferedReader(UTF_8).use { it.readText() }
        val error = process.errorStream.bufferedReader(UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0 && !(secret == null && exitCode == 1)) {
            throw IllegalStateException(error.ifBlank { "secret-tool exited with code $exitCode" })
        }
        return output.trimEnd()
    }

    private fun runPowerShell(operation: String, key: String, input: String): String {
        val target = "Yuno/$key"
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            Add-Type -TypeDefinition @'
            using System;
            using System.ComponentModel;
            using System.Runtime.InteropServices;

            [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
            public struct YunoCredential {
                public UInt32 Flags;
                public UInt32 Type;
                [MarshalAs(UnmanagedType.LPWStr)] public String TargetName;
                [MarshalAs(UnmanagedType.LPWStr)] public String Comment;
                public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
                public UInt32 CredentialBlobSize;
                public IntPtr CredentialBlob;
                public UInt32 Persist;
                public UInt32 AttributeCount;
                public IntPtr Attributes;
                [MarshalAs(UnmanagedType.LPWStr)] public String TargetAlias;
                [MarshalAs(UnmanagedType.LPWStr)] public String UserName;
            }

            public static class YunoCredentialApi {
                [DllImport("Advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
                private static extern bool CredWrite(ref YunoCredential credential, UInt32 flags);
                [DllImport("Advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
                private static extern bool CredRead(String target, UInt32 type, UInt32 flags, out IntPtr credential);
                [DllImport("Advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
                private static extern bool CredDelete(String target, UInt32 type, UInt32 flags);
                [DllImport("Advapi32.dll")] private static extern void CredFree(IntPtr credential);

                public static void Write(String target, byte[] value) {
                    if (value.Length > 2560) throw new InvalidOperationException("The compressed session is too large for Windows Credential Manager.");
                    IntPtr blob = Marshal.AllocHGlobal(value.Length);
                    try {
                        Marshal.Copy(value, 0, blob, value.Length);
                        var credential = new YunoCredential {
                            Type = 1,
                            TargetName = target,
                            CredentialBlobSize = (UInt32)value.Length,
                            CredentialBlob = blob,
                            Persist = 2,
                            UserName = "Yuno"
                        };
                        if (!CredWrite(ref credential, 0)) throw new Win32Exception(Marshal.GetLastWin32Error());
                    } finally {
                        for (int i = 0; i < value.Length; i++) Marshal.WriteByte(blob, i, 0);
                        Marshal.FreeHGlobal(blob);
                    }
                }

                public static String Read(String target) {
                    IntPtr pointer;
                    if (!CredRead(target, 1, 0, out pointer)) {
                        int code = Marshal.GetLastWin32Error();
                        if (code == 1168) return "";
                        throw new Win32Exception(code);
                    }
                    try {
                        var credential = (YunoCredential)Marshal.PtrToStructure(pointer, typeof(YunoCredential));
                        byte[] value = new byte[credential.CredentialBlobSize];
                        if (value.Length > 0) Marshal.Copy(credential.CredentialBlob, value, 0, value.Length);
                        return Convert.ToBase64String(value);
                    } finally {
                        CredFree(pointer);
                    }
                }

                public static void Delete(String target) {
                    if (!CredDelete(target, 1, 0)) {
                        int code = Marshal.GetLastWin32Error();
                        if (code != 1168) throw new Win32Exception(code);
                    }
                }
            }
            '@
            ${'$'}operation = '$operation'
            ${'$'}target = '$target'
            if (${'$'}operation -eq 'write') {
                ${'$'}encoded = [Console]::In.ReadToEnd()
                [YunoCredentialApi]::Write(${'$'}target, [Convert]::FromBase64String(${'$'}encoded))
            } elseif (${'$'}operation -eq 'read') {
                [Console]::Write([YunoCredentialApi]::Read(${'$'}target))
            } else {
                [YunoCredentialApi]::Delete(${'$'}target)
            }
        """.trimIndent()

        val process = ProcessBuilder(
            "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-Command", script,
        ).start()
        if (input.isNotEmpty()) {
            process.outputStream.bufferedWriter(UTF_8).use { it.write(input) }
        } else {
            process.outputStream.close()
        }
        val stdout = process.inputStream.bufferedReader(UTF_8).use { it.readText() }
        val stderr = process.errorStream.bufferedReader(UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) { stderr.ifBlank { "Windows Credential Manager failed with exit code $exitCode" } }
        return stdout.trim()
    }
}
