//! What a note holds besides text and tables: pictures, scans, PDFs, recordings, films and
//! other files — read so a note can show them where they sit and open them.
//!
//! Measured on a real account: an `Attachment` record names its kind (`UTIEncrypted`, e.g.
//! `public.jpeg`, `com.apple.paper.doc.pdf`, `com.apple.m4a-audio`) and points by its `Media`
//! reference at a `Media` record, whose `Asset` is the file itself (`downloadURL`) and whose
//! `FilenameEncrypted` is its name. Pictures, scans, PDFs and films also carry
//! `PreviewImages` — small renderings, a few at different sizes — with `PreviewWidths` /
//! `PreviewHeights`, and pictures their `Width` / `Height`. Every asset is served as
//! `application/octet-stream`, so what a file is gets read from its own bytes.
//!
//! Asset addresses expire, so the records are looked up fresh each time a file is fetched.

use crate::cloudkit::ZoneClient;
use crate::{Error, Result};
use serde::Serialize;
use serde_json::Value;

const CONTAINER: &str = "com.apple.notes";
const ZONE: &str = "Notes";

/// What an attachment is, to draw it in its note.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentInfo {
    pub id: String,
    pub uti: String,
    /// `image`, `drawing`, `table`, `document` (PDF, scan), `scan` (an old scan: a gallery of
    /// pages), `link` (a web address's card), `audio`, `video`, `inline` (a tag or a mention,
    /// drawn as its text), or `file`.
    pub kind: &'static str,
    pub filename: Option<String>,
    pub title: Option<String>,
    pub width: Option<f64>,
    pub height: Option<f64>,
    pub size: Option<i64>,
    /// Whether Apple keeps a rendering of it to show in place of the file.
    pub has_preview: bool,
    /// An old scan's pages, in the order Apple shows them: picture attachments of their own.
    #[serde(default)]
    pub pages: Vec<ScanPage>,
    /// A web link's address (`public.url`), the card's way out.
    #[serde(default)]
    pub url: Option<String>,
}

/// One page of an old scan: a picture attachment, with the size Apple keeps for it.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ScanPage {    pub id: String,
    pub width: Option<f64>,
    pub height: Option<f64>,
}

/// The kind of thing a type identifier names, as Notes draws it.
#[must_use]
pub fn kind_of(uti: &str) -> &'static str {
    let u = uti.to_ascii_lowercase();
    // A drawing made in the note (iOS 17's inline drawings, and the older drawing kinds):
    // shown as Apple renders it, never as a file.
    if matches!(
        u.as_str(),
        "com.apple.paper" | "com.apple.drawing" | "com.apple.drawing.2"
    ) {
        return "drawing";
    }
    // Text set inside a line — a #tag (`…inlinetextattachment.hashtag`) or an @mention: drawn
    // as the words it shows, never as a file.
    if u.starts_with("com.apple.notes.inlinetextattachment") {
        return "inline";
    }
    // A table: drawn as its grid, never offered as a file to open.
    if u == crate::table::UTI {
        return "table";
    }
    // An old scan (before iOS 17): a gallery record whose pages are picture attachments of
    // their own, drawn as the pictures they are.
    if u == "com.apple.notes.gallery" {
        return "scan";
    }
    // A web address kept as a link card: its title, address and Apple's rendering of it.
    if u == "public.url" {
        return "link";
    }
    if matches!(
        u.as_str(),
        "public.jpeg"
            | "public.png"
            | "public.heic"
            | "public.heif"
            | "com.compuserve.gif"
            | "public.tiff"
            | "org.webmproject.webp"
            | "public.image"
    ) {
        "image"
    } else if u.contains("pdf") || u.contains("scan") {
        "document"
    } else if u.contains("audio") || u.ends_with(".mp3") || u.contains("m4a") {
        "audio"
    } else if u.contains("movie")
        || u.contains("video")
        || u.contains("mpeg-4") && !u.contains("audio")
    {
        "video"
    } else {
        "file"
    }
}

fn decoded(record: &Value, field: &str) -> Option<String> {
    use base64::Engine;
    let v = record
        .pointer(&format!("/fields/{field}/value"))?
        .as_str()?;
    let bytes = base64::engine::general_purpose::STANDARD.decode(v).ok()?;
    let text = String::from_utf8(bytes).ok()?;
    let t = text.trim().to_string();
    (!t.is_empty()).then_some(t)
}

fn number(record: &Value, field: &str) -> Option<f64> {
    record.pointer(&format!("/fields/{field}/value"))?.as_f64()
}

/// What the attachment is, read from Apple now.
pub async fn info(
    http: &reqwest::Client,
    ckdatabasews_root: &str,
    params: Vec<(String, String)>,
    id: &str,
) -> Result<AttachmentInfo> {
    let client = ZoneClient::new(http, ckdatabasews_root, CONTAINER, params);
    let record = client
        .lookup_record(ZONE, id)
        .await?
        .ok_or(Error::NotFound)?;
    let uti = decoded(&record, "UTIEncrypted")
        .or_else(|| {
            record
                .pointer("/fields/UTI/value")
                .and_then(Value::as_str)
                .map(str::to_string)
        })
        .unwrap_or_default();
    if kind_of(&uti) == "inline" {
        return Ok(inline_info(id, uti, &record));
    }
    if kind_of(&uti) == "scan" {
        return scan_info(&client, id, uti, &record).await;
    }
    if kind_of(&uti) == "link" {
        return Ok(link_info(id, uti, &record));
    }
    let media = match record
        .pointer("/fields/Media/value/recordName")
        .and_then(Value::as_str)
    {
        Some(m) => client.lookup_record(ZONE, m).await?,
        None => None,
    };
    // A paper document's file is its PDF, named from its title.
    let paper = media.is_none() && record.pointer("/fields/FallbackPDF/value").is_some();
    let filename = media
        .as_ref()
        .and_then(|m| decoded(m, "FilenameEncrypted"))
        .or_else(|| {
            paper.then(|| {
                format!(
                    "{}.pdf",
                    decoded(&record, "TitleEncrypted").unwrap_or_else(|| "Document".into())
                )
            })
        });
    Ok(AttachmentInfo {
        id: id.to_string(),
        kind: kind_of(&uti),
        filename,
        title: decoded(&record, "TitleEncrypted"),
        width: number(&record, "Width"),
        height: number(&record, "Height"),
        size: record
            .pointer("/fields/FileSize/value")
            .and_then(Value::as_i64)
            .or_else(|| {
                media
                    .as_ref()
                    .and_then(|m| m.pointer("/fields/Asset/value/size"))
                    .and_then(Value::as_i64)
            })
            .or_else(|| {
                record
                    .pointer("/fields/FallbackPDF/value/size")
                    .and_then(Value::as_i64)
            }),
        has_preview: record
            .pointer("/fields/PreviewImages/value")
            .and_then(Value::as_array)
            .is_some_and(|a| !a.is_empty()),
        pages: Vec::new(),
        url: None,
        uti,
    })
}

/// The type identifier of an attachment Apple still holds for `note`, ready to be put back
/// where it was — the record a save that restores one (an Undo of a delete) refers to again.
/// `None` when Apple holds none: the record gone, marked deleted, or another note's. Nothing
/// is written on a `None`: a note referring to an attachment that is not there is what made
/// Notes on a phone lag and crash.
pub async fn restorable(
    http: &reqwest::Client,
    ckdatabasews_root: &str,
    params: Vec<(String, String)>,
    note: &str,
    id: &str,
) -> Result<Option<String>> {
    let client = ZoneClient::new(http, ckdatabasews_root, CONTAINER, params);
    let Some(record) = client.lookup_record(ZONE, id).await? else {
        return Ok(None);
    };
    if record
        .pointer("/fields/Deleted/value")
        .and_then(Value::as_i64)
        .unwrap_or(0)
        != 0
    {
        return Ok(None);
    }
    if record
        .pointer("/fields/Note/value/recordName")
        .and_then(Value::as_str)
        != Some(note)
    {
        return Ok(None);
    }
    let uti = decoded(&record, "UTIEncrypted")
        .or_else(|| {
            record
                .pointer("/fields/UTI/value")
                .and_then(Value::as_str)
                .map(str::to_string)
        })
        .unwrap_or_default();
    Ok((!uti.is_empty()).then_some(uti))
}

/// A tag or mention, with the words it shows as its title. Notes keeps those words as the
/// attachment's alt text, cased as typed, and a tag's normalised name as its token (upper
/// case, no `#`); either is read, plain or in its `…Encrypted` form.
fn inline_info(id: &str, uti: String, record: &Value) -> AttachmentInfo {
    let field = |name: &str| {
        decoded(record, &format!("{name}Encrypted")).or_else(|| {
            record
                .pointer(&format!("/fields/{name}/value"))
                .and_then(Value::as_str)
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
        })
    };
    let hashtag = uti.to_ascii_lowercase().ends_with(".hashtag");
    let title = field("AltText").or_else(|| {
        field("TokenContentIdentifier").map(|t| {
            if hashtag && !t.starts_with('#') {
                format!("#{t}")
            } else {
                t
            }
        })
    });
    AttachmentInfo {
        id: id.to_string(),
        kind: "inline",
        filename: None,
        title,
        width: None,
        height: None,
        size: None,
        has_preview: false,
        pages: Vec::new(),
        url: None,
        uti,
    }
}

/// An old scan: a gallery record with no file of its own. Its pages are picture attachments
/// named, in order, in its `MergeableDataEncrypted` (measured 2026-11-16: every gallery's
/// data names exactly its children, each `"$<uuid>"`). Each page is read once here so the
/// note knows how large to draw it; a page Apple no longer holds, or one that belongs to
/// another attachment, is left out.
async fn scan_info(
    client: &ZoneClient<'_>,
    id: &str,
    uti: String,
    record: &Value,
) -> Result<AttachmentInfo> {
    let ids = gallery_pages(record);
    let found = client.lookup_records(ZONE, &ids).await?;
    let pages = ids
        .into_iter()
        .filter_map(|page| {
            let record = found.get(&page)?;
            let belongs = record
                .pointer("/fields/ParentAttachment/value/recordName")
                .and_then(Value::as_str)
                == Some(id);
            let gone = record
                .pointer("/fields/Deleted/value")
                .and_then(Value::as_bool)
                == Some(true);
            (belongs && !gone).then(|| ScanPage {
                width: number(record, "Width"),
                height: number(record, "Height"),
                id: page,
            })
        })
        .collect();
    Ok(AttachmentInfo {
        id: id.to_string(),
        kind: "scan",
        filename: None,
        title: decoded(record, "TitleEncrypted").or_else(|| decoded(record, "UserTitle")),
        width: number(record, "Width"),
        height: number(record, "Height"),
        size: None,
        has_preview: record
            .pointer("/fields/PreviewImages/value")
            .and_then(Value::as_array)
            .is_some_and(|a| !a.is_empty()),
        pages,
        url: None,
        uti,
    })
}

/// A web address kept in a note: its card's title and address, and Apple's rendering of the
/// page when it made one. There is no file to fetch: the address is what opens.
fn link_info(id: &str, uti: String, record: &Value) -> AttachmentInfo {
    let url = decoded(record, "URLStringEncrypted").or_else(|| {
        record
            .pointer("/fields/URLString/value")
            .and_then(Value::as_str)
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
    });
    AttachmentInfo {
        id: id.to_string(),
        kind: "link",
        filename: None,
        title: decoded(record, "TitleEncrypted"),
        width: number(record, "Width"),
        height: number(record, "Height"),
        size: record
            .pointer("/fields/FileSize/value")
            .and_then(Value::as_i64),
        has_preview: record
            .pointer("/fields/PreviewImages/value")
            .and_then(Value::as_array)
            .is_some_and(|a| !a.is_empty()),
        pages: Vec::new(),
        url,
        uti,
    }
}

/// The pages of an old scan, in the order Apple shows them: the `"$<uuid>"` names in its
/// `MergeableDataEncrypted` — base64 over gzip over protobuf, measured 2026-11-16. Nothing
/// readable is no pages, never a failure: the note then says Apple holds none.
fn gallery_pages(record: &Value) -> Vec<String> {
    use base64::Engine;
    let Some(compressed) = record
        .pointer("/fields/MergeableDataEncrypted/value")
        .and_then(Value::as_str)
        .and_then(|b64| base64::engine::general_purpose::STANDARD.decode(b64).ok())
    else {
        return Vec::new();
    };
    let Some(plain) = crate::bounded::gunzip(&compressed, 4 * 1024 * 1024) else {
        return Vec::new();
    };
    let mut ids = Vec::new();
    collect_page_ids(&plain, 0, &mut ids);
    ids.truncate(MOST_PAGES);
    ids
}

/// The most pages one scan is read as: far past any the account held (12 was the most).
const MOST_PAGES: usize = 200;

/// The `"$<uuid>"` names in a protobuf message, wherever Apple buried them, in the order read.
/// Only a length-delimited field of exactly Apple's 38 bytes is a page; everything else is
/// walked into. Depth and count are bounded: a malformed record is no pages, not a hang.
fn collect_page_ids(buf: &[u8], depth: usize, out: &mut Vec<String>) {
    if depth > 8 || out.len() >= MOST_PAGES {
        return;
    }
    let mut at = 0usize;
    while at < buf.len() && out.len() < MOST_PAGES {
        let Some((key, n)) = varint(&buf[at..]) else {
            return;
        };
        at += n;
        match key & 7 {
            0 => {
                let Some((_, n)) = varint(&buf[at..]) else {
                    return;
                };
                at += n;
            }
            1 => at += 8,
            5 => at += 4,
            2 => {
                let Some((len, n)) = varint(&buf[at..]) else {
                    return;
                };
                at += n;
                let len = usize::try_from(len).unwrap_or(usize::MAX);
                if at + len > buf.len() {
                    return;
                }
                let part = &buf[at..at + len];
                at += len;
                match page_id(part) {
                    Some(id) if !out.contains(&id) => out.push(id),
                    Some(_) => {}
                    None => collect_page_ids(part, depth + 1, out),
                }
            }
            _ => return,
        }
    }
}

/// The id in a page's name: `"$` and a 36-character UUID, exactly as Apple writes it.
fn page_id(name: &[u8]) -> Option<String> {
    if name.len() != 38 || name[..2] != *b"\"$" {
        return None;
    }
    let id = std::str::from_utf8(&name[2..]).ok()?;
    id.chars()
        .all(|c| c.is_ascii_hexdigit() || c == '-')
        .then(|| id.to_string())
}

/// Protobuf's base-128 varint: the value and how many bytes it took.
fn varint(buf: &[u8]) -> Option<(u64, usize)> {
    let mut value = 0u64;
    for (i, &b) in buf.iter().enumerate().take(10) {
        value |= u64::from(b & 0x7f) << (7 * i);
        if b & 0x80 == 0 {
            return Some((value, i + 1));
        }
    }
    None
}

/// Whether a description kept earlier still says what Apple's type identifier says the
/// attachment is. A scan or a link read before Skykeep knew those kinds says `file`, and is
/// read again rather than left a card that cannot open.
#[must_use]
pub fn kept_is_current(json: &str) -> bool {
    #[derive(serde::Deserialize)]
    struct Kept {
        uti: String,
        kind: String,
    }
    serde_json::from_str::<Kept>(json).is_ok_and(|kept| kept.kind == kind_of(&kept.uti))
}

/// Which bytes of an attachment to fetch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Part {
    /// The file itself.
    File,
    /// Apple's largest rendering of it.
    Preview,
    /// A drawing as Apple renders it: its fallback image (twice its size, ink on white), or
    /// its largest preview when it has none.
    Drawing,
}

async fn download(http: &reqwest::Client, url: &str) -> Result<Vec<u8>> {
    // CloudKit's asset addresses carry a `${f}` for a filename of the caller's choosing.
    let response = http.get(url.replace("${f}", "file")).send().await?;
    if !response.status().is_success() {
        return Err(Error::Protocol(format!(
            "Apple would not hand over that attachment ({})",
            response.status()
        )));
    }
    Ok(response.bytes().await?.to_vec())
}

/// An attachment's bytes, fetched from Apple now.
pub async fn bytes(
    http: &reqwest::Client,
    ckdatabasews_root: &str,
    params: Vec<(String, String)>,
    id: &str,
    part: Part,
) -> Result<Vec<u8>> {
    let client = ZoneClient::new(http, ckdatabasews_root, CONTAINER, params);
    let record = client
        .lookup_record(ZONE, id)
        .await?
        .ok_or(Error::NotFound)?;
    if part == Part::Drawing {
        if let Some(url) = record
            .pointer("/fields/FallbackImage/value/downloadURL")
            .and_then(Value::as_str)
        {
            return download(http, url).await;
        }
    }
    match part {
        Part::Preview | Part::Drawing => {
            let previews = record
                .pointer("/fields/PreviewImages/value")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            let widths: Vec<f64> = record
                .pointer("/fields/PreviewWidths/value")
                .and_then(Value::as_array)
                .map(|a| a.iter().filter_map(Value::as_f64).collect())
                .unwrap_or_default();
            // The largest Apple keeps.
            let best = (0..previews.len()).max_by(|&a, &b| {
                widths
                    .get(a)
                    .unwrap_or(&0.0)
                    .partial_cmp(widths.get(b).unwrap_or(&0.0))
                    .unwrap_or(std::cmp::Ordering::Equal)
            });
            let url = best
                .and_then(|i| previews.get(i))
                .and_then(|p| p.get("downloadURL"))
                .and_then(Value::as_str)
                .ok_or(Error::NotFound)?;
            download(http, url).await
        }
        Part::File => {
            // A PDF or scan made in Notes ("paper") keeps no Media record: its own format is in
            // `PaperDatabase`, and a ready PDF of it in `FallbackPDF` — what any other app opens.
            if let Some(url) = record
                .pointer("/fields/FallbackPDF/value/downloadURL")
                .and_then(Value::as_str)
            {
                if record.pointer("/fields/Media/value/recordName").is_none() {
                    return download(http, url).await;
                }
            }
            let media = record
                .pointer("/fields/Media/value/recordName")
                .and_then(Value::as_str)
                .ok_or(Error::NotFound)?;
            let media = client
                .lookup_record(ZONE, media)
                .await?
                .ok_or(Error::NotFound)?;
            // An attachment Apple keeps at size 0 (an old drawing from 2016) has no bytes, and
            // its address answers 400 (coddingtonbear/icloud-md#55): it is empty, not refused.
            if media
                .pointer("/fields/Asset/value/size")
                .and_then(Value::as_u64)
                == Some(0)
            {
                return Ok(Vec::new());
            }
            let url = media
                .pointer("/fields/Asset/value/downloadURL")
                .and_then(Value::as_str)
                .ok_or(Error::NotFound)?;
            download(http, url).await
        }
    }
}

/// The characters of an attachment id kept in a file name: an id from a request is never a path.
#[must_use]
pub fn file_id(id: &str) -> String {
    id.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// The name a part is kept under on disk, the same one the desktop has always used.
#[must_use]
pub fn part_name(part: Part) -> &'static str {
    match part {
        Part::File => "file",
        Part::Preview => "preview",
        Part::Drawing => "drawing",
    }
}

/// An attachment's bytes kept on disk beside the app's own files: from there when they have
/// been fetched before, from Apple when not — so a note opens on its pictures again at once,
/// and offline.
pub struct Cache {
    dir: std::path::PathBuf,
}

impl Cache {
    #[must_use]
    pub fn new(dir: impl Into<std::path::PathBuf>) -> Self {
        Self { dir: dir.into() }
    }

    /// Where `id`'s part is kept ("file", "preview", "drawing"), whether or not it is there.
    #[must_use]
    pub fn path(&self, id: &str, name: &str) -> std::path::PathBuf {
        self.dir.join(format!("{}-{name}", file_id(id)))
    }

    /// The bytes already kept for `id`'s part, if they are.
    #[must_use]
    pub fn kept(&self, id: &str, name: &str) -> Option<Vec<u8>> {
        std::fs::read(self.path(id, name)).ok()
    }

    /// `id`'s part: from disk when it is kept there, from `fetch` when not, and kept for the
    /// next time. A read or a write that fails is not the fetch failing: the bytes are still
    /// handed over, and simply not kept.
    pub async fn get_or_fetch<E, F, Fut>(
        &self,
        id: &str,
        name: &str,
        fetch: F,
    ) -> std::result::Result<Vec<u8>, E>
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = std::result::Result<Vec<u8>, E>>,
    {
        let path = self.path(id, name);
        if let Ok(bytes) = tokio::fs::read(&path).await {
            return Ok(bytes);
        }
        let bytes = fetch().await?;
        if let Some(dir) = path.parent() {
            tokio::fs::create_dir_all(dir).await.ok();
        }
        // Whole or not at all: written beside it and renamed in, so a second request for the
        // same picture (a drawing's light and dark ink, the viewer) never reads half a file —
        // which the window took for a broken picture and showed as a file for the session.
        let part = path.with_extension("part");
        if tokio::fs::write(&part, &bytes).await.is_ok() {
            let _ = tokio::fs::rename(&part, &path).await;
        }
        Ok(bytes)
    }
}

#[cfg(test)]
mod empty_asset_tests {
    use super::*;
    use wiremock::matchers::{body_string_contains, method, path_regex};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    /// An attachment whose file Apple keeps at size 0 opens empty; asked for, its address
    /// answers 400, and opening it said Apple would not hand it over.
    #[tokio::test]
    async fn an_empty_attachment_is_empty_not_refused() {
        let server = MockServer::start().await;
        let lookup = |name: &str, record: serde_json::Value| {
            Mock::given(method("POST"))
                .and(path_regex(r".*/records/lookup$"))
                .and(body_string_contains(name.to_string()))
                .respond_with(
                    ResponseTemplate::new(200)
                        .set_body_json(serde_json::json!({ "records": [record] })),
                )
        };
        lookup(
            "\"ATT\"",
            serde_json::json!({ "recordName": "ATT", "recordType": "Attachment", "recordChangeTag": "t1",
                "fields": { "Media": { "value": { "recordName": "MEDIA" } } } }),
        )
        .mount(&server)
        .await;
        lookup(
            "\"MEDIA\"",
            serde_json::json!({ "recordName": "MEDIA", "recordType": "Media", "recordChangeTag": "t2",
                "fields": { "Asset": { "value": { "size": 0,
                    "downloadURL": format!("{}/asset/${{f}}", server.uri()) } } } }),
        )
        .mount(&server)
        .await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(400))
            .mount(&server)
            .await;
        let got = bytes(
            &reqwest::Client::new(),
            &server.uri(),
            vec![],
            "ATT",
            Part::File,
        )
        .await
        .expect("an empty file, not a refusal");
        assert!(got.is_empty());
        let asked = server.received_requests().await.unwrap();
        assert!(
            asked.iter().all(|r| r.method.as_str() != "GET"),
            "nothing downloaded"
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{body_string_contains, method, path_regex};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    /// The kinds measured on a real account, each drawn the way Notes draws it.
    #[test]
    fn each_kind_measured_is_drawn_as_notes_draws_it() {
        assert_eq!(kind_of("public.jpeg"), "image");
        assert_eq!(kind_of("com.apple.paper.doc.pdf"), "document");
        assert_eq!(kind_of("com.apple.paper.doc.scan"), "document");
        // Measured: an iOS 17 drawing in his "Colors" note is com.apple.paper, with a
        // FallbackImage (1536×1242 for a 768×621 drawing) and two previews.
        assert_eq!(kind_of("com.apple.paper"), "drawing");
        assert_eq!(kind_of("com.apple.drawing.2"), "drawing");
        // A table is never a file to open, even one the table reader cannot read.
        assert_eq!(kind_of("com.apple.notes.table"), "table");
        assert_eq!(kind_of("com.apple.m4a-audio"), "audio");
        assert_eq!(kind_of("public.mpeg-4-audio"), "audio");
        assert_eq!(kind_of("com.apple.quicktime-movie"), "video");
        assert_eq!(kind_of("org.openxmlformats.spreadsheetml.sheet"), "file");
        assert_eq!(
            kind_of("dyn.ah62d4rv4ge80455crf0gn3dfs3y0g3puqfy1e4psq6"),
            "file"
        );
        // Measured: the scans made before iOS 17 are a gallery whose pages are pictures of
        // their own; a web link is a card, not a file.
        assert_eq!(kind_of("com.apple.notes.gallery"), "scan");
        assert_eq!(kind_of("public.url"), "link");
    }

    fn put_varint(mut value: u64, out: &mut Vec<u8>) {
        loop {
            let b = (value & 0x7f) as u8;
            value >>= 7;
            if value == 0 {
                out.push(b);
                return;
            }
            out.push(b | 0x80);
        }
    }

    fn varint_field(field: u64, value: u64) -> Vec<u8> {
        let mut out = Vec::new();
        put_varint(field << 3, &mut out);
        put_varint(value, &mut out);
        out
    }

    fn bytes_field(field: u64, payload: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        put_varint((field << 3) | 2, &mut out);
        put_varint(payload.len() as u64, &mut out);
        out.extend_from_slice(payload);
        out
    }

    /// A gallery's `MergeableDataEncrypted` as Apple writes it: gzip over a protobuf whose
    /// page objects carry `"$<uuid>"`, in the order the pages are shown (measured 2026-11-16).
    fn gallery_mergeable(ids: &[&str]) -> String {
        use base64::Engine;
        use std::io::Write;
        let mut body = varint_field(1, 0);
        body.extend(varint_field(2, 0));
        for id in ids {
            let name = format!("\"${id}");
            let inner = bytes_field(1, &varint_field(1, 0));
            let mut page = inner;
            page.extend(bytes_field(2, name.as_bytes()));
            let three = bytes_field(3, &page);
            let thirteen = bytes_field(13, &three);
            body.extend(bytes_field(3, &thirteen));
        }
        let mut top = varint_field(1, 0);
        top.extend(bytes_field(2, &body));
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(&top).unwrap();
        base64::engine::general_purpose::STANDARD.encode(encoder.finish().unwrap())
    }

    /// A scan's pages come from the gallery's own data, in Apple's order — not the order the
    /// children happen to arrive in, which is none at all.
    #[test]
    fn a_gallery_lists_its_pages_in_apples_order() {
        let ids = [
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
        ];
        let record = serde_json::json!({ "fields": {
            "MergeableDataEncrypted": { "value": gallery_mergeable(&ids) },
        }});
        assert_eq!(gallery_pages(&record), ids.map(str::to_string).to_vec());
        // Nothing readable is no pages, never a panic.
        assert!(gallery_pages(&serde_json::json!({ "fields": {} })).is_empty());
        assert!(gallery_pages(&serde_json::json!({ "fields": {
            "MergeableDataEncrypted": { "value": "not base64!" },
        }}))
        .is_empty());
    }

    /// A description kept before Skykeep knew what a scan or a link is says `file`; it is read
    /// again rather than left a card that cannot open. A current one is used as it is.
    #[test]
    fn a_description_from_before_a_kind_was_known_is_read_again() {
        for (uti, was, now) in [
            ("com.apple.notes.gallery", "file", "scan"),
            ("public.url", "file", "link"),
            ("public.jpeg", "image", "image"),
        ] {
            let kept = format!(r#"{{"id":"A","uti":"{uti}","kind":"{was}"}}"#);
            assert_eq!(kept_is_current(&kept), was == now);
            let kept = format!(r#"{{"id":"A","uti":"{uti}","kind":"{now}"}}"#);
            assert!(kept_is_current(&kept));
        }
        assert!(!kept_is_current("{not json"));
    }

    /// A scan read from Apple: its kind, its title, and its pages in order, each with the size
    /// Apple keeps for it. A page that belongs to something else is not one of its pages.
    #[tokio::test]
    async fn a_scan_is_read_as_its_pages() {
        use base64::Engine;
        let b64 = |s: &str| base64::engine::general_purpose::STANDARD.encode(s);
        let server = MockServer::start().await;
        let p1 = "11111111-1111-4111-8111-111111111111";
        let p2 = "22222222-2222-4222-8222-222222222222";
        let stranger = "44444444-4444-4444-8444-444444444444";
        let page = |name: &str, parent: &str, w: f64, h: f64| {
            serde_json::json!({ "recordName": name, "recordType": "Attachment",
                "recordChangeTag": "t", "fields": {
                    "UTIEncrypted": { "value": b64("public.jpeg") },
                    "ParentAttachment": { "value": { "recordName": parent } },
                    "Width": { "value": w }, "Height": { "value": h },
                }})
        };
        let gallery = serde_json::json!({ "recordName": "GAL", "recordType": "Attachment",
            "recordChangeTag": "t", "fields": {
                "UTIEncrypted": { "value": b64("com.apple.notes.gallery") },
                "TitleEncrypted": { "value": b64("Scan") },
                "MergeableDataEncrypted": { "value": gallery_mergeable(&[p1, p2, stranger]) },
                "PreviewImages": { "value": [{ "downloadURL": "x" }] },
            }});
        Mock::given(method("POST"))
            .and(path_regex(r".*/records/lookup$"))
            .and(body_string_contains("\"GAL\""))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({ "records": [gallery] })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path_regex(r".*/records/lookup$"))
            .and(body_string_contains(p1))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({ "records": [
                page(p1, "GAL", 1024.0, 768.0),
                page(p2, "GAL", 800.0, 600.0),
                page(stranger, "OTHER", 10.0, 10.0),
            ]})))
            .mount(&server)
            .await;
        let info = info(&reqwest::Client::new(), &server.uri(), vec![], "GAL")
            .await
            .expect("a scan");
        assert_eq!(info.kind, "scan");
        assert_eq!(info.title.as_deref(), Some("Scan"));
        assert_eq!(
            info.pages,
            vec![
                ScanPage { id: p1.into(), width: Some(1024.0), height: Some(768.0) },
                ScanPage { id: p2.into(), width: Some(800.0), height: Some(600.0) },
            ]
        );
    }

    /// A web link in a note is a card with its address, never a file Apple will not hand over.
    #[tokio::test]
    async fn a_link_is_read_as_its_address() {
        use base64::Engine;
        let b64 = |s: &str| base64::engine::general_purpose::STANDARD.encode(s);
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path_regex(r".*/records/lookup$"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({ "records": [{
                "recordName": "URL", "recordType": "Attachment", "recordChangeTag": "t",
                "fields": {
                    "UTIEncrypted": { "value": b64("public.url") },
                    "TitleEncrypted": { "value": b64("A page") },
                    "URLStringEncrypted": { "value": b64("https://example.com/a") },
                }}]})))
            .mount(&server)
            .await;
        let info = info(&reqwest::Client::new(), &server.uri(), vec![], "URL")
            .await
            .expect("a link");
        assert_eq!(info.kind, "link");
        assert_eq!(info.title.as_deref(), Some("A page"));
        assert_eq!(info.url.as_deref(), Some("https://example.com/a"));
        assert!(info.pages.is_empty());
    }

    /// A tag shows the words it was typed as; with only its token, the token behind a `#`.
    #[test]
    fn a_tag_is_drawn_as_its_words() {
        use base64::Engine;
        let b64 = |s: &str| base64::engine::general_purpose::STANDARD.encode(s);
        let uti = "com.apple.notes.inlinetextattachment.hashtag";
        assert_eq!(kind_of(uti), "inline");
        assert_eq!(
            kind_of("com.apple.notes.inlinetextattachment.mention"),
            "inline"
        );
        let typed = serde_json::json!({ "fields": {
            "AltTextEncrypted": { "value": b64("#Recipes") },
            "TokenContentIdentifierEncrypted": { "value": b64("RECIPES") },
        }});
        let info = inline_info("A", uti.into(), &typed);
        assert_eq!(
            (info.kind, info.title.as_deref()),
            ("inline", Some("#Recipes"))
        );
        let token = serde_json::json!({ "fields": {
            "TokenContentIdentifier": { "value": "RECIPES" },
        }});
        assert_eq!(
            inline_info("A", uti.into(), &token).title.as_deref(),
            Some("#RECIPES")
        );
        let bare = serde_json::json!({ "fields": {} });
        assert_eq!(inline_info("A", uti.into(), &bare).title, None);
    }

    /// A picture fetched once is served from disk the next time: opening the note again asks
    /// Apple nothing, and a request that never reached Apple before is fetched once and kept.
    #[tokio::test]
    async fn a_picture_is_fetched_once_and_kept() {
        let dir = std::env::temp_dir().join(format!("skykeep-note-media-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let cache = Cache::new(&dir);
        let asked = std::sync::atomic::AtomicUsize::new(0);
        let fetched = cache
            .get_or_fetch::<std::io::Error, _, _>("P-1", "file", || async {
                asked.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Ok(b"picture".to_vec())
            })
            .await
            .unwrap();
        assert_eq!(fetched, b"picture");
        assert_eq!(cache.kept("P-1", "file").as_deref(), Some(&b"picture"[..]));
        let kept = cache
            .get_or_fetch::<std::io::Error, _, _>("P-1", "file", || async {
                unreachable!("asked Apple for a picture already kept")
            })
            .await
            .unwrap();
        assert_eq!(kept, b"picture");
        assert_eq!(asked.load(std::sync::atomic::Ordering::SeqCst), 1);
        // Another part of the same attachment is its own file.
        assert!(!cache.path("P-1", "preview").exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// An id from a request names no path: only letters, digits and `-` reach the file name.
    #[test]
    fn an_attachment_id_cannot_climb_out_of_the_cache() {
        assert_eq!(file_id("A/b\\c..d"), "A_b_c__d");
        assert_eq!(part_name(Part::File), "file");
        assert_eq!(part_name(Part::Preview), "preview");
        assert_eq!(part_name(Part::Drawing), "drawing");
    }
}
