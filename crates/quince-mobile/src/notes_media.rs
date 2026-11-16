//! What a note holds besides text: pictures, drawings, documents and other files, and the
//! small picture the list shows beside a note.
//!
//! The bytes come from Apple once and are kept under the engine's own data directory, quite as
//! the desktop keeps them (`quince_icloud::note_media::Cache`), so a note opens on its pictures
//! again at once and offline. What an attachment is is kept in the library under the desktop's
//! own key; the list's small picture is made from what is already kept and never fetched for.

use crate::notes_rich::{cloudkit_context, with_notes};
use crate::{failed, runtime, Account, EngineError};
use quince_core::Library;
use quince_icloud::note_media::{self, Cache, Part};

/// Where the library keeps what an attachment is: the desktop's own key, so both shells read
/// one entry.
pub(crate) fn attachment_key(id: &str) -> String {
    format!("notes.attachment.{id}")
}

/// Where a note's attachment bytes are kept on this phone: beside the engine's own files.
fn cache(account: &Account) -> Cache {
    Cache::new(account.dir.join("note-media"))
}

/// What an attachment is, from the library when it was read before; `None` (asked of Apple
/// again) when it was never read or what is kept does not read as JSON.
pub(crate) fn attachment_info(l: &Library, id: &str) -> Option<String> {
    l.state(&attachment_key(id))
        .ok()
        .flatten()
        .filter(|kept| serde_json::from_str::<serde_json::Value>(kept).is_ok())
}

/// Each note's first picture already on the phone, by note: the list's small picture. Read from
/// what this phone holds and never fetched for the list.
pub(crate) fn pictures_json(l: &Library, cache: &Cache) -> quince_core::Result<String> {
    let mut out = serde_json::Map::new();
    for (guid, id) in l.note_first_pictures()? {
        let kept = ["inline", "drawing", "preview", "file"]
            .iter()
            .any(|name| cache.path(&id, name).is_file());
        if kept {
            out.insert(guid, serde_json::Value::String(id));
        }
    }
    Ok(serde_json::Value::Object(out).to_string())
}

/// A note's small picture: the first part of it already on the phone, and nothing fetched.
fn thumb(cache: &Cache, id: &str) -> Result<Vec<u8>, EngineError> {
    ["inline", "drawing", "preview", "file"]
        .iter()
        .find_map(|name| cache.kept(id, name))
        .ok_or_else(|| EngineError::Failed {
            reason: "That picture isn't on this phone yet.".into(),
        })
}

/// An attachment's part: from the phone when kept, from Apple when not, and kept after.
fn part_bytes(
    account: &Account,
    cache: &Cache,
    id: &str,
    name: &str,
    part: Part,
) -> Result<Vec<u8>, EngineError> {
    runtime().block_on(cache.get_or_fetch(id, name, || async {
        let (http, root, params) = cloudkit_context(account)?;
        note_media::bytes(&http, &root, params, id, part)
            .await
            .map_err(failed)
    }))
}

#[uniffi::export]
impl Account {
    /// What an attachment is — a picture, a drawing, a document, a file — as the desktop's
    /// `note_attachment_info` answers it; kept in the library so drawing the note asks Apple
    /// nothing the next time.
    pub fn note_attachment_info_json(&self, id: String) -> Result<String, EngineError> {
        if let Some(kept) = with_notes(self, |l| Ok(attachment_info(l, &id)))? {
            return Ok(kept);
        }
        let (http, root, params) = cloudkit_context(self)?;
        let info = runtime()
            .block_on(note_media::info(&http, &root, params, &id))
            .map_err(failed)?;
        let json = serde_json::to_string(&info).map_err(failed)?;
        with_notes(self, |l| l.set_state(&attachment_key(&id), &json))?;
        Ok(json)
    }

    /// An attachment's bytes for `part`: `file`, `inline`, `preview`, `drawing`,
    /// `drawing-dark` or `thumb` (the list's small picture, from what is already kept).
    pub fn note_media(&self, id: String, part: String) -> Result<Vec<u8>, EngineError> {
        let cache = cache(self);
        match part.as_str() {
            "thumb" => thumb(&cache, &id),
            "file" | "inline" => part_bytes(self, &cache, &id, &part, Part::File),
            "preview" => part_bytes(self, &cache, &id, "preview", Part::Preview),
            "drawing" | "drawing-dark" => part_bytes(self, &cache, &id, "drawing", Part::Drawing),
            other => Err(EngineError::Failed {
                reason: format!("“{other}” is not one of an attachment's parts."),
            }),
        }
    }

    /// Each note's first picture this phone already holds, by note: the attachment whose small
    /// picture the list shows beside it. Nothing is fetched for the list.
    pub fn note_pictures_json(&self) -> Result<String, EngineError> {
        let cache = cache(self);
        with_notes(self, |l| pictures_json(l, &cache))
    }
}

#[cfg(test)]
mod tests {
    use super::{attachment_info, attachment_key, pictures_json, thumb};
    use quince_core::notes::Note;
    use quince_core::Library;
    use quince_icloud::note_media::Cache;

    fn note(lib: &mut Library, guid: &str, attachment: &str) {
        let runs = format!(r#"[{{"length":1,"attachment":"{attachment}"}}]"#);
        lib.put_notes(&[Note {
            guid: guid.into(),
            folder_guid: None,
            title: guid.into(),
            snippet: None,
            body: Some("\u{FFFC}".into()),
            locked: false,
            pinned: false,
            created_at: Some(1),
            modified_at: Some(1),
            editable: true,
            etag: None,
            runs: Some(runs),
        }])
        .unwrap();
    }

    fn info(lib: &mut Library, id: &str) {
        lib.set_state(
            &attachment_key(id),
            &format!(r#"{{"id":"{id}","uti":"public.jpeg","kind":"image"}}"#),
        )
        .unwrap();
    }

    /// What an attachment is comes from the library when it was read before: drawing a note
    /// asks Apple nothing.
    #[test]
    fn attachment_info_uses_what_was_kept() {
        let mut lib = Library::in_memory().unwrap();
        info(&mut lib, "A");
        assert_eq!(
            attachment_info(&lib, "A").as_deref(),
            Some(r#"{"id":"A","uti":"public.jpeg","kind":"image"}"#)
        );
        assert_eq!(attachment_info(&lib, "never-read"), None);
        // Kept but not JSON: read again from Apple, never handed to the page.
        lib.set_state(&attachment_key("B"), "{not json").unwrap();
        assert_eq!(attachment_info(&lib, "B"), None);
    }

    /// The list's small picture is a picture already on the phone: one never drawn is passed
    /// over, and nothing is fetched for the list.
    #[test]
    fn pictures_only_lists_pictures_already_kept() {
        let mut lib = Library::in_memory().unwrap();
        note(&mut lib, "N1", "A");
        note(&mut lib, "N2", "B");
        info(&mut lib, "A");
        info(&mut lib, "B");
        let dir = std::env::temp_dir().join(format!("skykeep-pictures-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let cache = Cache::new(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(cache.path("A", "preview"), b"small").unwrap();
        let json = pictures_json(&lib, &cache).unwrap();
        let map: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(map, serde_json::json!({ "N1": "A" }));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// A picture from the list is served from what is kept; a part never fetched is an error,
    /// not a request to Apple.
    #[test]
    fn a_thumb_is_served_only_when_the_picture_is_kept() {
        let dir = std::env::temp_dir().join(format!("skykeep-thumb-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let cache = Cache::new(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        assert!(thumb(&cache, "P-1").is_err());
        std::fs::write(cache.path("P-1", "preview"), b"small").unwrap();
        assert_eq!(thumb(&cache, "P-1").unwrap(), b"small");
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
