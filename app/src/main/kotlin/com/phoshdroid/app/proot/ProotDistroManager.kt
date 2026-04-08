package com.phoshdroid.app.proot

import java.io.File

data class ProcessResult(val exitCode: Int, val output: String)

interface ProcessRunner {
    fun run(command: List<String>, environment: Map<String, String> = emptyMap()): ProcessResult
    fun start(command: List<String>, environment: Map<String, String> = emptyMap()): Process
}

class SystemProcessRunner : ProcessRunner {
    override fun run(command: List<String>, environment: Map<String, String>): ProcessResult {
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        environment.forEach { (k, v) -> pb.environment()[k] = v }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, output)
    }

    override fun start(command: List<String>, environment: Map<String, String>): Process {
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        environment.forEach { (k, v) -> pb.environment()[k] = v }
        return pb.start()
    }
}

class ProotDistroManager(
    private val commandBuilder: ProotCommandBuilder,
    private val installedRootfsDir: File,
    private val rootfsTarball: File,
    private val processRunner: ProcessRunner = SystemProcessRunner()
) {

    private val distroDir = File(installedRootfsDir, "postmarketos")

    fun isInstalled(): Boolean = distroDir.exists() && distroDir.isDirectory

    fun login(
        startupScript: String,
        bindSdcard: Boolean = false
    ): Process {
        return processRunner.start(
            commandBuilder.buildLoginCommand(startupScript, bindSdcard),
            commandBuilder.buildEnvironment()
        )
    }
}
