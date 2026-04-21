// Copyright (c) 2026 Echelon Team. All rights reserved.

//! Tar+gzip archive extraction helper. Streams decompression + unpack
//! w jednym kroku — bez intermediate buffer pełnego pliku w pamięci.
//!
//! Używane przez `updater.rs` dla launcher.tar.gz + jre-<os>.tar.gz.
//! Paczki są strukturalnie czyste (brak absolute paths, brak ../ traversal),
//! ale tar crate i tak sanitizes ścieżki domyślnie — drugi poziom obrony.
//!
//! **Security notes:**
//! - `set_preserve_permissions(false)` — chroni przed CVE-2026-33056 (tar
//!   symlink-chmod attack na non-Windows targets). Na Windows permissions
//!   to no-op, więc wartość `false` jest idempotent; na Linuxie wymusza
//!   że extract NIE zmienia chmod docelowych plików (tylko zapisuje data).
//! - `set_unpack_xattrs(false)` — explicit defense-in-depth. Default w tar
//!   0.4 jest `false`, ale jawne wywołanie chroni przed potencjalną zmianą
//!   defaults w przyszłych bump deps (recommended przez tar-rs issue #165).
//! - `set_overwrite(true)` — pozwala nadpisywać istniejące pliki w
//!   target_dir. Update flow używa tego po `rename install_dir/launcher/
//!   → launcher.old/`, więc target jest pusty, ale w razie re-entry
//!   (np. crash podczas extract) chcemy dokończyć zamiast fail.
//! - Path traversal (`../` w tar headers): empirycznie zweryfikowane na
//!   tar 0.4.45 — `..` entries są cicho pomijane (Ok returned, plik nie
//!   powstaje ani w target ani w parent). Regression guard w testach
//!   `extract_rejects_parent_traversal` + `extract_rejects_absolute_path`.
//!
//! **Partial-failure contract:** `archive.unpack()` może fail mid-way
//! zostawiając częściowo wypakowane pliki w target_dir. Caller odpowiedzialny
//! za cleanup przed rollback — updater.rs (Task 4) robi to via rename
//! `launcher/` → `launcher.old/` przed extract, więc fresh target_dir.
//! W razie extract-mid-fail: drop target_dir, rollback z `launcher.old/`.

use crate::{Result, UpdaterError};
use flate2::bufread::GzDecoder;
use std::fs::{self, File};
use std::io::BufReader;
use std::path::Path;

/// Extract tar.gz archive pod `target_dir`. Dir jest tworzony jeśli nie istnieje
/// (idempotent — caller może przekazać nieistniejący path, bez muszenia pre-create).
///
/// Zachowuje relatywne ścieżki z archiwum. Tar crate sanitizes paths
/// (cicho pomija `..` albo absolute). Nie zachowuje Unix perms
/// (`set_preserve_permissions=false`), nie zachowuje xattrs
/// (`set_unpack_xattrs=false`).
///
/// Cały flow jest streaming — decompress + unpack chunk-by-chunk, nie
/// wymaga załadowania całości do pamięci.
pub fn extract_tar_gz(archive_path: &Path, target_dir: &Path) -> Result<()> {
    fs::create_dir_all(target_dir).map_err(|e| UpdaterError::Extract {
        context: format!("create target dir {}", target_dir.display()),
        source: e,
    })?;
    let file = File::open(archive_path).map_err(|e| UpdaterError::Extract {
        context: format!("open archive {}", archive_path.display()),
        source: e,
    })?;
    let buf_reader = BufReader::new(file);
    let gz_decoder = GzDecoder::new(buf_reader);
    let mut archive = tar::Archive::new(gz_decoder);
    archive.set_preserve_permissions(false);
    archive.set_unpack_xattrs(false);
    archive.set_overwrite(true);
    archive.unpack(target_dir).map_err(|e| UpdaterError::Extract {
        context: format!("unpack to {}", target_dir.display()),
        source: e,
    })?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::io::Write;
    use tempfile::tempdir;

    /// Tworzy test tar.gz z podaną listą plików.
    /// Każdy wpis: (path, bytes) → tar::Header + append.
    fn write_test_archive(path: &Path, entries: &[(&str, &[u8])]) {
        let file = File::create(path).unwrap();
        let gz_encoder = GzEncoder::new(file, Compression::default());
        let mut builder = tar::Builder::new(gz_encoder);
        for (name, data) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_path(name).unwrap();
            header.set_size(data.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder.append(&header, *data).unwrap();
        }
        builder.finish().unwrap();
    }

    /// Tworzy tar.gz z jawnie skonstruowanym raw tar header żeby wstrzyknąć
    /// "invalid" path jak `../escape.txt` lub `/etc/passwd` (tar crate
    /// `Builder::append` sam by takie odrzucił, więc omijamy go przez
    /// bezpośredni write header bytes).
    fn write_adversarial_archive(path: &Path, raw_path: &[u8], data: &[u8]) {
        let mut header = [0u8; 512];
        header[..raw_path.len()].copy_from_slice(raw_path);
        // mode 0644
        header[100..108].copy_from_slice(b"0000644\0");
        // uid / gid zero
        header[108..116].copy_from_slice(b"0000000\0");
        header[116..124].copy_from_slice(b"0000000\0");
        // size (11 octal digits + NUL)
        let size_octal = format!("{:011o}\0", data.len());
        header[124..136].copy_from_slice(size_octal.as_bytes());
        // mtime zero
        header[136..148].copy_from_slice(b"00000000000\0");
        // typeflag '0' = regular file
        header[156] = b'0';
        // magic "ustar\0" + version "00"
        header[257..263].copy_from_slice(b"ustar\0");
        header[263..265].copy_from_slice(b"00");
        // checksum: fill with spaces, compute, overwrite
        header[148..156].copy_from_slice(b"        ");
        let sum: u32 = header.iter().map(|b| u32::from(*b)).sum();
        let chk = format!("{sum:06o}\0 ");
        header[148..156].copy_from_slice(chk.as_bytes());

        // Data padded to 512-byte block
        let mut data_block = [0u8; 512];
        data_block[..data.len()].copy_from_slice(data);

        // Gzip wrap: header + data + 2 zero blocks (end marker)
        let f = File::create(path).unwrap();
        let mut gz = GzEncoder::new(f, Compression::default());
        gz.write_all(&header).unwrap();
        gz.write_all(&data_block).unwrap();
        gz.write_all(&[0u8; 1024]).unwrap();
        gz.finish().unwrap();
    }

    #[test]
    fn extract_tar_gz_unpacks_plain_files() {
        let tmp = tempdir().unwrap();
        let archive = tmp.path().join("test.tar.gz");
        let extract_to = tmp.path().join("extracted");

        write_test_archive(
            &archive,
            &[
                ("hello.txt", b"Hello, World!"),
                ("sub/dir/file.bin", b"\x00\x01\x02\x03"),
            ],
        );

        extract_tar_gz(&archive, &extract_to).unwrap();

        assert_eq!(
            fs::read_to_string(extract_to.join("hello.txt")).unwrap(),
            "Hello, World!"
        );
        assert_eq!(
            fs::read(extract_to.join("sub/dir/file.bin")).unwrap(),
            vec![0x00, 0x01, 0x02, 0x03]
        );
    }

    #[test]
    fn extract_tar_gz_creates_target_dir_if_missing() {
        // Invariant: fs::create_dir_all na początku — caller nie musi
        // pre-create target.
        let tmp = tempdir().unwrap();
        let archive = tmp.path().join("test.tar.gz");
        let nonexistent_target = tmp.path().join("nonexistent/nested/target");
        assert!(!nonexistent_target.exists());

        write_test_archive(&archive, &[("file.txt", b"content")]);
        extract_tar_gz(&archive, &nonexistent_target).unwrap();
        assert!(nonexistent_target.is_dir());
        assert!(nonexistent_target.join("file.txt").exists());
    }

    #[test]
    fn extract_tar_gz_overwrites_existing_files() {
        // set_overwrite(true) invariant: jeśli plik istnieje w target_dir,
        // extract go nadpisuje (nie rzuca error).
        let tmp = tempdir().unwrap();
        let archive = tmp.path().join("test.tar.gz");
        let extract_to = tmp.path().join("extracted");
        fs::create_dir(&extract_to).unwrap();

        // Pre-populate target z "stale" content
        fs::write(extract_to.join("hello.txt"), b"stale content").unwrap();

        write_test_archive(&archive, &[("hello.txt", b"fresh content")]);
        extract_tar_gz(&archive, &extract_to).unwrap();

        assert_eq!(
            fs::read_to_string(extract_to.join("hello.txt")).unwrap(),
            "fresh content"
        );
    }

    #[test]
    fn extract_tar_gz_missing_archive_returns_extract_error() {
        let tmp = tempdir().unwrap();
        let missing = tmp.path().join("does-not-exist.tar.gz");
        let extract_to = tmp.path().join("out");

        let result = extract_tar_gz(&missing, &extract_to);
        assert!(matches!(result, Err(UpdaterError::Extract { .. })));
    }

    #[test]
    fn extract_tar_gz_creates_nested_directories() {
        let tmp = tempdir().unwrap();
        let archive = tmp.path().join("test.tar.gz");
        let extract_to = tmp.path().join("extracted");

        write_test_archive(&archive, &[("a/b/c/deep.txt", b"deep file")]);
        extract_tar_gz(&archive, &extract_to).unwrap();

        assert!(extract_to.join("a").is_dir());
        assert!(extract_to.join("a/b").is_dir());
        assert!(extract_to.join("a/b/c").is_dir());
        assert_eq!(
            fs::read_to_string(extract_to.join("a/b/c/deep.txt")).unwrap(),
            "deep file"
        );
    }

    #[test]
    fn extract_tar_gz_rejects_parent_traversal() {
        // Adversarial archive z `../escape.txt` path entry. Tar 0.4.45
        // (empirycznie zweryfikowane): path jest cicho pomijany — target
        // pozostaje pusty, parent dir BEZ pliku escape.txt.
        // Regression guard: jeśli upgrade tar kiedyś zmieni zachowanie
        // (np. Err zamiast silent skip), invariant "nic nie trafia poza
        // target_dir" dalej musi być zachowany.
        let tmp = tempdir().unwrap();
        let archive = tmp.path().join("evil.tar.gz");
        let target = tmp.path().join("target");
        fs::create_dir_all(&target).unwrap();

        write_adversarial_archive(&archive, b"../escape.txt", b"pwned");

        // Extract może zwrócić Ok (silent skip) lub Err — both acceptable.
        // Critical invariant: NIC poza target_dir.
        let _ = extract_tar_gz(&archive, &target);
        assert!(
            !tmp.path().join("escape.txt").exists(),
            "path traversal escaped target_dir"
        );
        assert!(
            !target.join("escape.txt").exists(),
            "relative ../ was unexpectedly normalized inside target"
        );
    }

    #[test]
    fn extract_tar_gz_rejects_absolute_path() {
        // Adversarial archive z absolute path. Tar crate powinien silently
        // pomijać lub Err — never write to absolute path poza target.
        let tmp = tempdir().unwrap();
        let archive = tmp.path().join("evil.tar.gz");
        let target = tmp.path().join("target");
        fs::create_dir_all(&target).unwrap();
        let absolute_escape = tmp.path().join("absolute_escape.txt");

        // Platform-appropriate absolute path. Na Windows bez drive letter
        // "/" może być interpretowany jako relative-to-root of current drive.
        #[cfg(unix)]
        let raw_path = absolute_escape.to_string_lossy().into_owned();
        #[cfg(windows)]
        let raw_path = absolute_escape.to_string_lossy().replace('\\', "/");

        write_adversarial_archive(&archive, raw_path.as_bytes(), b"pwned");

        let _ = extract_tar_gz(&archive, &target);
        assert!(
            !absolute_escape.exists(),
            "absolute path wrote outside target"
        );
    }

    #[test]
    fn extract_tar_gz_truncated_gzip_returns_extract_error() {
        // Malformed gzip stream (truncated mid-compression) powinien
        // propagować error przez GzDecoder → Archive::unpack → nasz wariant.
        // NIE silent success.
        let tmp = tempdir().unwrap();
        let full = tmp.path().join("full.tar.gz");
        let truncated = tmp.path().join("trunc.tar.gz");
        let target = tmp.path().join("target");
        fs::create_dir_all(&target).unwrap();

        write_test_archive(&full, &[("file.txt", b"hello world, this is content")]);
        let bytes = fs::read(&full).unwrap();
        fs::write(&truncated, &bytes[..bytes.len() / 2]).unwrap();

        let result = extract_tar_gz(&truncated, &target);
        assert!(matches!(result, Err(UpdaterError::Extract { .. })));
    }

    #[test]
    fn extract_tar_gz_invalid_gzip_magic_returns_extract_error() {
        // Plik który nie jest gzip (brak magic bytes 1f 8b) powinien
        // szybko fail przez GzDecoder header check.
        let tmp = tempdir().unwrap();
        let bogus = tmp.path().join("bogus.tar.gz");
        let target = tmp.path().join("target");
        fs::create_dir_all(&target).unwrap();

        fs::write(&bogus, b"this is not a gzip file, just some text").unwrap();

        let result = extract_tar_gz(&bogus, &target);
        assert!(matches!(result, Err(UpdaterError::Extract { .. })));
    }

    #[test]
    fn extract_error_preserves_source_chain() {
        // `UpdaterError::Extract { source: io::Error }` z `#[source]` —
        // weryfikacja że `Error::source()` zwraca original io::Error
        // (nie None), żeby diagnostics chain działał.
        let tmp = tempdir().unwrap();
        let missing = tmp.path().join("nope.tar.gz");
        let target = tmp.path().join("target");

        let err = extract_tar_gz(&missing, &target).unwrap_err();
        use std::error::Error;
        let source = err.source();
        assert!(source.is_some(), "Extract error must expose source chain");
    }
}
