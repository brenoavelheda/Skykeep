//! A note's formatting, as the phone's editor reads and writes it.
//!
//! Runs cross the bridge as the JSON string the desktop's editor already speaks
//! (`quince_icloud::ckdocument::Run`), and every save goes through the same
//! `quince_sync::notes::record_note_save` the desktop and the rest of the phone use, so no
//! text-to-runs conversion or merge is written twice. A note opened with no stored formatting
//! opens plain; one whose formatting does not describe its text is saved as text alone, and
//! Apple's own formatting stays where the engine's merge keeps it.

use crate::{failed, runtime, Account, EngineError};
use quince_core::Library;
use quince_icloud::ckdocument::{carry_runs, Run};
use quince_sync::notes as shared;

/// The engine's Notes library, one call at a time, as `notes.rs` takes it. Each new module of
/// the bridge says the same thing in its own words so `notes.rs` keeps only what it had.
pub(crate) fn with_notes<T>(
    account: &Account,
    f: impl FnOnce(&mut Library) -> quince_core::Result<T>,
) -> Result<T, EngineError> {
    let (lock, _) = account.library()?;
    runtime().block_on(async {
        let mut l = lock.lock().await;
        f(&mut l).map_err(failed)
    })
}

/// The same, for a call that answers its own `EngineError` (a save refused in words the person
/// reads, not the engine's storage error).
pub(crate) fn with_notes_engine<T>(
    account: &Account,
    f: impl FnOnce(&mut Library) -> Result<T, EngineError>,
) -> Result<T, EngineError> {
    let (lock, _) = account.library()?;
    runtime().block_on(async {
        let mut l = lock.lock().await;
        f(&mut l)
    })
}

/// What a call to Apple's Notes needs: the client, CloudKit's address, the session's parameters.
pub(crate) type Apple = (reqwest::Client, String, Vec<(String, String)>);

/// The session's CloudKit connection for Notes, as the desktop's `cloudkit_context` gives it.
pub(crate) fn cloudkit_context(account: &Account) -> Result<Apple, EngineError> {
    account
        .service("ckdatabasews")
        .ok_or_else(|| failed("Apple hasn't offered Notes for this account."))
}

/// The note is gone: deleted here or on another device.
pub(crate) fn gone() -> quince_core::Error {
    quince_core::Error::Io(std::io::Error::other(
        "This note isn't here any more: it was deleted, here or on another device.",
    ))
}

/// A refusal in the engine's own words: what reaches the screen.
fn refused(reason: impl Into<String>) -> quince_core::Error {
    quince_core::Error::Io(std::io::Error::other(reason.into()))
}

/// A note's stored runs, as Apple keeps them; `None` when none are stored or they cannot be
/// read (the note then opens plain).
pub(crate) fn stored_runs(l: &Library, guid: &str) -> quince_core::Result<Option<Vec<Run>>> {
    Ok(l.note_runs(guid)?
        .and_then(|r| serde_json::from_str(&r).ok()))
}

/// Apple's runs, as the page sends them: malformed JSON is an error, never a panic and never a
/// silently dropped formatting.
pub(crate) fn parse_runs(json: &str) -> Result<Vec<Run>, EngineError> {
    serde_json::from_str(json).map_err(|_| EngineError::Failed {
        reason: "This note’s formatting couldn’t be read, so nothing was saved.".into(),
    })
}

/// A note's runs as the page reads them: the stored JSON when it is readable, `[]` when there
/// is none (or it is not).
pub(crate) fn runs_json(l: &Library, guid: &str) -> quince_core::Result<String> {
    Ok(match l.note_runs(guid)? {
        Some(stored) if serde_json::from_str::<Vec<Run>>(&stored).is_ok() => stored,
        _ => "[]".to_string(),
    })
}

/// The note's text as Markdown, set the way Notes sets it: the desktop's own exporter.
pub(crate) fn markdown(body: &str, runs_json: &str) -> Result<String, EngineError> {
    let runs = parse_runs(runs_json)?;
    Ok(quince_icloud::markdown::note_to_markdown(body, &runs))
}

/// A save from the page: `runs_json = None` is the plain save the phone had (formatting
/// carried from what the text was); with runs, what the page shows is written exactly, against
/// the text and formatting it began from.
pub(crate) fn save_formatted(
    l: &mut Library,
    guid: &str,
    body: &str,
    base: Option<String>,
    runs_json: Option<String>,
    base_runs_json: Option<String>,
) -> Result<(), EngineError> {
    let runs = runs_json.as_deref().map(parse_runs).transpose()?;
    let base_runs = base_runs_json.as_deref().map(parse_runs).transpose()?;
    save(l, guid, body, base, runs, base_runs).map_err(failed)
}

fn save(
    l: &mut Library,
    guid: &str,
    body: &str,
    base: Option<String>,
    runs: Option<Vec<Run>>,
    base_runs: Option<Vec<Run>>,
) -> quince_core::Result<()> {
    let Some(note) = l.note(guid)? else {
        return Err(gone());
    };
    let Some(runs) = runs else {
        // The phone's text save: the note's formatting is carried over from the text it was, so
        // every heading, checklist and link stays where it was.
        let before = l.note_body(guid)?.ok_or_else(gone)?;
        let base_runs = stored_runs(l, guid)?;
        let runs = base_runs.as_ref().map(|r| carry_runs(&before, r, body));
        return shared::record_note_save(l, guid, body, Some(before), runs, base_runs);
    };
    if !note.editable {
        return Err(refused(
            "This note holds formatting Skykeep can't rewrite safely, so it stays read-only.",
        ));
    }
    shared::record_note_save(l, guid, body, base, Some(runs), base_runs)
}

#[uniffi::export]
impl Account {
    /// A note's text; `None` when the library holds none (no such note, or its text not read
    /// yet).
    pub fn note_body(&self, guid: String) -> Result<Option<String>, EngineError> {
        with_notes(self, |l| l.note_body(&guid))
    }

    /// How a note's text is set — Apple's attribute runs, in order — as the page reads them.
    /// `[]` when none is stored.
    pub fn note_runs_json(&self, guid: String) -> Result<String, EngineError> {
        with_notes(self, |l| runs_json(l, &guid))
    }

    /// A note's text saved with its formatting, through the one write path the desktop and the
    /// phone share. `runs_json = None` is the plain save the phone had; the note's formatting
    /// is carried from what the text was.
    pub fn note_save_formatted(
        &self,
        guid: String,
        body: String,
        base: Option<String>,
        runs_json: Option<String>,
        base_runs_json: Option<String>,
    ) -> Result<(), EngineError> {
        with_notes_engine(self, |l| {
            save_formatted(l, &guid, &body, base, runs_json, base_runs_json)
        })
    }

    /// A note as Markdown, ready to save or share: the desktop's own exporter, over the text
    /// and runs the editor shows.
    pub fn note_markdown(&self, body: String, runs_json: String) -> Result<String, EngineError> {
        markdown(&body, &runs_json)
    }
}

#[cfg(test)]
mod tests {
    use super::{markdown, runs_json, save_formatted, stored_runs};
    use crate::EngineError;
    use quince_core::notes::Note;
    use quince_core::Library;
    use quince_icloud::ckdocument::{carry_runs, Run};

    fn note(lib: &mut Library, guid: &str, body: &str, runs: &[Run]) {
        lib.put_notes(&[Note {
            guid: guid.into(),
            folder_guid: None,
            title: body.lines().next().unwrap_or_default().into(),
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

    fn json(runs: &[Run]) -> String {
        serde_json::to_string(runs).unwrap()
    }

    /// What is saved with formatting is read back with it: the same text and the same runs,
    /// clip for clip.
    #[test]
    fn runs_json_round_trips() {
        let mut lib = Library::in_memory().unwrap();
        let body = "Hello world\n";
        let runs = vec![
            Run {
                length: 5,
                ..Run::default()
            },
            Run {
                length: 7,
                style: Some(103),
                checked: Some(false),
                ..Run::default()
            },
        ];
        note(&mut lib, "N", "", &[]);
        save_formatted(&mut lib, "N", body, None, Some(json(&runs)), None).unwrap();
        assert_eq!(lib.note_body("N").unwrap().as_deref(), Some(body));
        assert_eq!(runs_json(&lib, "N").unwrap(), json(&runs));
        assert_eq!(stored_runs(&lib, "N").unwrap().as_deref(), Some(&runs[..]));
    }

    /// Runs count UTF-16 units, as the page and Kotlin do: an emoji before a bold word does
    /// not shift the stretch onto another character.
    #[test]
    fn formatted_save_keeps_runs_after_emoji() {
        let mut lib = Library::in_memory().unwrap();
        let body = "😀 bold\n";
        assert_eq!(body.encode_utf16().count(), 8);
        let runs = vec![
            Run {
                length: 3,
                ..Run::default()
            },
            Run {
                length: 5,
                bold: true,
                ..Run::default()
            },
        ];
        note(&mut lib, "N", "", &[]);
        save_formatted(&mut lib, "N", body, None, Some(json(&runs)), None).unwrap();
        assert_eq!(lib.note_body("N").unwrap().as_deref(), Some(body));
        assert_eq!(runs_json(&lib, "N").unwrap(), json(&runs));
        assert_eq!(stored_runs(&lib, "N").unwrap().as_deref(), Some(&runs[..]));
    }

    /// With no runs the save is the plain one the phone had: formatting carried from what the
    /// text was, through the engine's own `carry_runs`.
    #[test]
    fn save_without_runs_carries_formatting() {
        let mut lib = Library::in_memory().unwrap();
        let before = "Title\nbold";
        let runs = vec![
            Run {
                length: 6,
                style: Some(0),
                ..Run::default()
            },
            Run {
                length: 4,
                bold: true,
                ..Run::default()
            },
        ];
        note(&mut lib, "N", before, &runs);
        let after = "Title\nbold still";
        save_formatted(&mut lib, "N", after, None, None, None).unwrap();
        assert_eq!(lib.note_body("N").unwrap().as_deref(), Some(after));
        assert_eq!(
            stored_runs(&lib, "N").unwrap().as_deref(),
            Some(&carry_runs(before, &runs, after)[..])
        );
    }

    /// A note with no stored formatting stays plain when saved without runs: `[]`, never a
    /// blank string the page cannot parse.
    #[test]
    fn a_plain_note_reads_as_an_empty_array() {
        let mut lib = Library::in_memory().unwrap();
        note(&mut lib, "N", "plain\n", &[]);
        assert_eq!(runs_json(&lib, "N").unwrap(), "[]");
        assert_eq!(runs_json(&lib, "missing").unwrap(), "[]");
    }

    /// Malformed runs are refused before anything is written: the note keeps its text and its
    /// formatting, and the engine says what went wrong instead of panicking.
    #[test]
    fn malformed_runs_json_is_an_error() {
        let mut lib = Library::in_memory().unwrap();
        let before = "Keep me\n";
        let runs = vec![Run {
            length: 8,
            bold: true,
            ..Run::default()
        }];
        note(&mut lib, "N", before, &runs);
        let err = save_formatted(
            &mut lib,
            "N",
            "changed",
            None,
            Some("{not json".into()),
            None,
        )
        .unwrap_err();
        assert!(matches!(err, EngineError::Failed { .. }), "{err:?}");
        assert_eq!(lib.note_body("N").unwrap().as_deref(), Some(before));
        assert_eq!(stored_runs(&lib, "N").unwrap().as_deref(), Some(&runs[..]));
        // The formatting the editor began from is held to the same rule.
        assert!(save_formatted(
            &mut lib,
            "N",
            "changed",
            Some(before.into()),
            Some(json(&runs)),
            Some("[".into())
        )
        .is_err());
    }

    /// Markdown is the desktop's own, character for character.
    #[test]
    fn markdown_matches_the_desktop() {
        let body = "Groceries\nmilk\n";
        let runs = vec![
            Run {
                length: 10,
                style: Some(0),
                ..Run::default()
            },
            Run {
                length: 5,
                style: Some(103),
                checked: Some(true),
                ..Run::default()
            },
        ];
        assert_eq!(
            markdown(body, &json(&runs)).unwrap(),
            quince_icloud::markdown::note_to_markdown(body, &runs)
        );
        assert!(markdown(body, "{not json").is_err());
    }
}
