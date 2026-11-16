//! A password-locked note: opened read-only with the password the person types, which is kept
//! in memory for the session only.
//!
//! What the desktop's window and the phone's engine share (`quince_icloud::notes::locked::
//! Passwords`): a password that opened a note is remembered until the app quits, the account
//! signs out, or the person locks notes again — never in the library, the settings or the
//! keyring, and a wrong one is forgotten, never kept. The opened text is handed to the page and
//! not stored either: a locked note's words never reach disk here.

use crate::notes_rich::cloudkit_context;
use crate::{failed, runtime, Account, EngineError};
use quince_icloud::notes::locked::{self, Passwords};

/// The password that opened each locked note this session, by note.
pub(crate) static PASSWORDS: Passwords = Passwords::new();

/// Every remembered password let go of: on sign-out, or when the phone locks notes again.
pub(crate) fn forget_passwords() {
    PASSWORDS.forget_all();
}

fn open(account: &Account, guid: &str, password: Option<String>) -> Result<String, EngineError> {
    let password = PASSWORDS
        .for_unlock(guid, password)
        .map_err(|reason| EngineError::Failed { reason })?;
    let (http, root, params) = cloudkit_context(account)?;
    match runtime().block_on(locked::unlock_cloudkit(
        &http, &root, params, guid, &password,
    )) {
        Ok(opened) => {
            PASSWORDS.remember(guid, &password);
            serde_json::to_string(&opened).map_err(failed)
        }
        Err(e) => {
            PASSWORDS.forget_one(guid);
            Err(failed(e))
        }
    }
}

#[uniffi::export]
impl Account {
    /// A locked note opened read-only: its text and formatting, as JSON with the desktop's
    /// `UnlockedNote` field names. With no password, the one that opened it earlier this
    /// session is used; a wrong one is forgotten, never kept.
    pub fn note_unlock_json(
        &self,
        guid: String,
        password: Option<String>,
    ) -> Result<String, EngineError> {
        open(self, &guid, password)
    }

    /// The hint left with a locked note's password, if any.
    pub fn note_lock_hint(&self, guid: String) -> Result<Option<String>, EngineError> {
        let (http, root, params) = cloudkit_context(self)?;
        runtime()
            .block_on(locked::hint_cloudkit(&http, &root, params, &guid))
            .map_err(failed)
    }

    /// Lock every opened note again: the passwords this session held are wiped.
    pub fn note_lock_forget(&self) {
        forget_passwords();
    }
}

#[cfg(test)]
mod tests {
    use super::{forget_passwords, PASSWORDS};

    /// The password that opened a note is remembered for the session; locking notes again
    /// forgets it, and the next open asks for it as the desktop's window does.
    #[test]
    fn lock_forget_drops_the_password() {
        forget_passwords();
        PASSWORDS.remember("NOTE", "hunter2");
        assert_eq!(PASSWORDS.for_unlock("NOTE", None).unwrap(), "hunter2");
        forget_passwords();
        assert_eq!(
            PASSWORDS.for_unlock("NOTE", None).unwrap_err(),
            "Enter this note’s password to open it."
        );
        // A wrong password never replaces the one that opened the note: it leaves none.
        PASSWORDS.remember("NOTE", "hunter2");
        PASSWORDS.forget_one("NOTE");
        assert!(PASSWORDS.for_unlock("NOTE", None).is_err());
    }
}
