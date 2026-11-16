//! Tables of a note: cells read from the attachment that holds them, and one cell changed.
//!
//! A table is not text: its cells live in the attachment's mergeable document, and the library
//! keeps the grid the last pass read (`table::state_key`). Setting a cell is the desktop's own
//! `set_table_cell_cloudkit`, written and kept the same way, so a table edited on the phone and
//! one edited on Windows leave Apple the same document.

use crate::notes_rich::{cloudkit_context, with_notes};
use crate::{failed, runtime, Account, EngineError};
use quince_core::Library;
use quince_icloud::table;

/// A table already kept in the library, as the page reads it: `None` when none has been read
/// (or what is kept does not read as a grid).
pub(crate) fn stored_json(l: &Library, id: &str) -> Option<String> {
    table::stored(l, id).and_then(|cells| serde_json::to_string(&cells).ok())
}

/// A table's cells read from Apple now, and kept, as the desktop asks for one a pass has not
/// read yet.
fn cells_from_apple(account: &Account, id: &str) -> Result<Option<Vec<Vec<String>>>, EngineError> {
    let (http, root, params) = cloudkit_context(account)?;
    runtime().block_on(async {
        let client =
            quince_icloud::cloudkit::ZoneClient::new(&http, &root, "com.apple.notes", params);
        let record = client.lookup_record("Notes", id).await.map_err(failed)?;
        Ok(record
            .as_ref()
            .and_then(|r| r.pointer("/fields/MergeableDataEncrypted/value"))
            .and_then(serde_json::Value::as_str)
            .and_then(table::cells_from_base64))
    })
}

#[uniffi::export]
impl Account {
    /// A table's cells, row by row, as the last pass read them; asked of Apple and kept when
    /// none are known yet, and `None` when there is no such table.
    pub fn note_table_json(&self, id: String) -> Result<Option<String>, EngineError> {
        if let Some(json) = with_notes(self, |l| Ok(stored_json(l, &id)))? {
            return Ok(Some(json));
        }
        let Some(cells) = cells_from_apple(self, &id)? else {
            return Ok(None);
        };
        let json = serde_json::to_string(&cells).map_err(failed)?;
        with_notes(self, |l| l.set_state(&table::state_key(&id), &json))?;
        Ok(Some(json))
    }

    /// One cell of a Notes table changed, and the table kept as Apple now holds it. Answers the
    /// whole grid, row by row.
    pub fn note_table_set_cell(
        &self,
        id: String,
        row: u32,
        column: u32,
        text: String,
    ) -> Result<String, EngineError> {
        let (http, root, params) = cloudkit_context(self)?;
        let row = usize::try_from(row).map_err(failed)?;
        let column = usize::try_from(column).map_err(failed)?;
        let cells = runtime()
            .block_on(quince_icloud::notes::set_table_cell_cloudkit(
                &http, &root, params, &id, row, column, &text,
            ))
            .map_err(failed)?;
        let json = serde_json::to_string(&cells).map_err(failed)?;
        with_notes(self, |l| l.set_state(&table::state_key(&id), &json))?;
        Ok(json)
    }
}

#[cfg(test)]
mod tests {
    use super::stored_json;
    use quince_core::Library;
    use quince_icloud::table;

    /// A table the last pass kept is answered from the library: nothing is asked of Apple, and
    /// one never read is `None` until it is.
    #[test]
    fn a_stored_table_is_answered_without_apple() {
        let mut lib = Library::in_memory().unwrap();
        lib.set_state(&table::state_key("T"), r#"[["a","b"],["c","d"]]"#)
            .unwrap();
        assert_eq!(
            stored_json(&lib, "T").as_deref(),
            Some(r#"[["a","b"],["c","d"]]"#)
        );
        assert_eq!(stored_json(&lib, "never-read"), None);
        // A stored value that does not read as a grid is no table, not a crash.
        lib.set_state(&table::state_key("B"), "not a grid").unwrap();
        assert_eq!(stored_json(&lib, "B"), None);
    }
}
