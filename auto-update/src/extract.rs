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
//! - `set_overwrite(true)` — pozwala nadpisywać istniejące pliki w
//!   target_dir. Update flow używa tego po `rename install_dir/launcher/
//!   → launcher.old/`, więc target jest pusty, ale w razie re-entry
//!   (np. crash podczas extract) chcemy dokończyć zamiast fail.

use crate::{Result, UpdaterError};
use flate2::bufread::GzDecoder;
use std::fs::File;
use std::io::BufReader;
use std::path::Path;

/// Extract tar.gz archive pod `target_dir`. Target dir musi istnieć.
///
/// Zachowuje relatywne ścieżki z archiwum. Tar crate sanitizes paths
/// (rzuca error na `..` albo absolute). Nie zachowuje Unix perms
/// (set_preserve_permissions=false), nie zachowuje xattrs.
///
/// Cały flow jest streaming — decompress + unpack chunk-by-chunk, nie
/// wymaga załadowania całości do pamięci.
pub fn extract_tar_gz(archive_path: &Path, target_dir: &Path) -> Result<()> {
    let file = File::open(archive_path).map_err(|e| {
        UpdaterError::Io(std::io::Error::new(
            e.kind(),
            format!("open archive {}: {}", archive_path.display(), e),
        ))
    })?;
    let buf_reader = BufReader::new(file);
    let gz_decoder = GzDecoder::new(buf_reader);
    let mut archive = tar::Archive::new(gz_decoder);
    archive.set_preserve_permissions(false);
    archive.set_overwrite(true);
    archive.unpack(target_dir).map_err(|e| {
        UpdaterError::Io(std::io::Error::new(
            e.kind(),
            format!("unpack to {}: {}", target_dir.display(), e),
        ))
    })?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::fs;
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

    #[test]
    fn extract_tar_gz_unpacks_plain_files() {
        let tmp = tempdir().unwrap();
        let archive = tmp.path().join("test.tar.gz");
        let extract_to = tmp.path().join("extracted");
        fs::create_dir(&extract_to).unwrap();

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
    fn extract_tar_gz_missing_archive_returns_io_error() {
        let tmp = tempdir().unwrap();
        let missing = tmp.path().join("does-not-exist.tar.gz");
        let extract_to = tmp.path().join("out");
        fs::create_dir(&extract_to).unwrap();

        let result = extract_tar_gz(&missing, &extract_to);
        assert!(matches!(result, Err(UpdaterError::Io(_))));
    }

    #[test]
    fn extract_tar_gz_creates_nested_directories() {
        // Zachowanie Archive::unpack: tworzy directory tree z wpisów
        // tar (bez explicit directory entries).
        let tmp = tempdir().unwrap();
        let archive = tmp.path().join("test.tar.gz");
        let extract_to = tmp.path().join("extracted");
        fs::create_dir(&extract_to).unwrap();

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
}
