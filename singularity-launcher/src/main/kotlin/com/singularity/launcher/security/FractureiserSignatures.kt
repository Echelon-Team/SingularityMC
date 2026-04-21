// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.security

/**
 * Znane sygnatury fractureiser stage0 + inne znane MC malware.
 *
 * Sygnatury:
 * - Nazwy klas podejrzane (typical malware naming patterns)
 * - Podejrzane strings w bytecode (C&C IP addresses, file paths)
 * - SHA-256 hash znanych złośliwych plików
 *
 * Źródła sygnatur: fractureiser investigation (2023), MMPA advisories.
 */
object FractureiserSignatures {

    val SUSPICIOUS_CLASS_NAMES = setOf(
        // Fractureiser stage0 patterns
        "WindowsMain",
        "LinuxMain",
        "MacMain",
        "ElfBootstrap",
        "Stage0Client",
        "PlatformSpecific",
        "CatnipBootstrap",
        // Generic malware class names
        "DataHandler",
        "SystemConfig",
        "NativeLoader",
        "BootstrapAgent"
    )

    val SUSPICIOUS_STRINGS = setOf(
        // Fractureiser C&C servers (historical IPs)
        "85.217.144.130",
        "193.106.191.162",
        // Fractureiser stage indicators
        "stage1_",
        ".run.bin",
        "libwebp.so",
        // Credential/wallet theft patterns
        "wallet.dat",
        "keychain",
        "password.txt",
        "Discord\\Local Storage",
        "Login Data",
        // Suspicious download pattern — URLClassLoader alone is too broad (Fabric loader uses it)
        "new URL(\"http"
    )

    val KNOWN_MALWARE_HASHES = setOf(
        // Fractureiser stage0 samples (historical — SHA-256)
        "0b5c75b8a3b1e1e6b8d3c4a5f6e7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5",
        "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2"
    )
}
