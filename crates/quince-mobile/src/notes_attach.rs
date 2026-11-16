//! A file added to a note from the phone: Apple's answer kept in the library at once.
//!
//! The upload and the note's document are `quince_icloud::notes::attach::attach_file_cloudkit`,
//! the desktop's own code. What comes back is laid over the library here as the desktop lays
//! it (`notes_cmds.rs` `lay_attached`): text, formatting with the file's character, a new time
//! edited, with anything still on its way to Apple laid back over it, so nothing typed is shown
//! lost while the file goes up.

use crate::notes_rich::{cloudkit_context, with_notes};
use crate::{failed, runtime, Account, EngineError};
use quince_core::Library;
use quince_icloud::notes::attach::Attached;
use quince_sync::notes as shared;

/// A note with a file just added, kept as Apple now holds it: its text and formatting as
/// written, its time edited moved on — and any edit still on its way to Apple laid back over
/// it, as a pass lays them, so nothing typed is shown lost meanwhile.
fn lay_attached(library: &mut Library, guid: &str, attached: &Attached) -> quince_core::Result<()> {
    let (title, snippet) = quince_icloud::notes::title_and_snippet(&attached.body);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_secs()).unwrap_or(0));
    library.atomically(|l| {
        l.set_note_text(
            guid,
            &attached.body,
            &title,
            Some(snippet.as_str()).filter(|s| !s.is_empty()),
        )?;
        if !attached.runs.is_empty() {
            let runs = serde_json::to_string(&attached.runs)
                .map_err(|e| quince_core::Error::Io(std::io::Error::other(e.to_string())))?;
            l.set_note_runs(guid, &runs)?;
        }
        l.touch_note(guid, now)?;
        shared::replay(l)
    })
}

#[uniffi::export]
impl Account {
    /// Add the file at `path` to a note at `at_offset` (UTF-16 units of `base`, the text the
    /// editor shows, or of Apple's text when no base is given). Answers what was added, in the
    /// desktop's `NoteAttached` shape, and keeps the library as Apple answered it.
    pub fn note_attach_file_json(
        &self,
        guid: String,
        path: String,
        at_offset: u32,
        base: Option<String>,
    ) -> Result<String, EngineError> {
        let (http, root, params) = cloudkit_context(self)?;
        let attached = runtime()
            .block_on(quince_icloud::notes::attach::attach_file_cloudkit(
                &http,
                &root,
                params,
                &guid,
                std::path::Path::new(&path),
                base.as_deref(),
                u64::from(at_offset),
            ))
            .map_err(failed)?;
        with_notes(self, |l| lay_attached(l, &guid, &attached))?;
        serde_json::to_string(&attached).map_err(failed)
    }
}

#[cfg(test)]
mod tests {
    use super::lay_attached;
    use quince_core::notes::Note;
    use quince_core::Library;
    use quince_icloud::ckdocument::Run;
    use quince_icloud::notes::attach::Attached;

    fn note(lib: &mut Library, body: &str, runs: &[Run]) {
        lib.put_notes(&[Note {
            guid: "N".into(),
            folder_guid: None,
            title: "Trip".into(),
            snippet: None,
            body: Some(body.into()),
            locked: false,
            pinned: false,
            created_at: Some(1),
            modified_at: Some(1),
            editable: true,
            etag: None,
            runs: Some(serde_json::to_string(runs).unwrap()),
        }])
        .unwrap();
    }

    fn attached(body: &str, runs: Vec<Run>) -> Attached {
        Attached {
            attachment: "A".into(),
            media: "M".into(),
            uti: "public.jpeg".into(),
            offset: 5,
            body: body.into(),
            runs,
        }
    }

    /// A file added is kept as Apple answered at once — text, formatting with the file's run, a
    /// new time edited — with no pass waited for.
    #[test]
    fn an_attached_file_is_laid_over_the_library() {
        let mut lib = Library::in_memory().unwrap();
        let before = vec![
            Run {
                length: 5,
                style: Some(0),
                ..Run::default()
            },
            Run {
                length: 4,
                ..Run::default()
            },
        ];
        note(&mut lib, "Trip\nend", &before);
        let runs = vec![
            Run {
                length: 5,
                style: Some(0),
                ..Run::default()
            },
            Run {
                length: 1,
                style: Some(0),
                attachment: Some("A".into()),
                ..Run::default()
            },
            Run {
                length: 1,
                ..Run::default()
            },
            Run {
                length: 3,
                ..Run::default()
            },
        ];
        lay_attached(
            &mut lib,
            "N",
            &attached("Trip\n\u{FFFC}\nend", runs.clone()),
        )
        .unwrap();
        assert_eq!(
            lib.note_body("N").unwrap().as_deref(),
            Some("Trip\n\u{FFFC}\nend")
        );
        let kept: Vec<Run> = serde_json::from_str(&lib.note_runs("N").unwrap().unwrap()).unwrap();
        assert_eq!(kept, runs);
        let n = lib.note("N").unwrap().unwrap();
        assert_eq!(n.title, "Trip");
        assert!(n.modified_at.unwrap() > 1);
    }

    /// Typing still on its way to Apple stays on screen over the note as Apple answered it.
    #[test]
    fn typing_not_yet_sent_stays_over_an_added_file() {
        let mut lib = Library::in_memory().unwrap();
        note(&mut lib, "Trip\nend", &[]);
        quince_sync::notes::record_note_save(
            &mut lib,
            "N",
            "Trip\nend and more",
            Some("Trip\nend".into()),
            None,
            None,
        )
        .unwrap();
        lay_attached(&mut lib, "N", &attached("Trip\n\u{FFFC}\nend", Vec::new())).unwrap();
        assert_eq!(
            lib.note_body("N").unwrap().as_deref(),
            Some("Trip\nend and more")
        );
    }
}
