// Copyright (c) 2026 AIMarketing
//
// Selector / anchor parser error type shared by every pc_automation
// sub-module (UIA selector, CDP selector, OCR anchor). Keeping the
// error in its own tiny module avoids a circular dependency between
// the parser and the types.

use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseError {
    /// The input string did not start with the expected prefix, e.g.
    /// `uia:`, `cdp:`, `ocr:`. Carries the offending prefix.
    InvalidPrefix(String),
    /// A required field was missing from the parsed representation.
    /// The string is a static label so the call site does not have to
    /// allocate a `String` for the common case.
    MissingField(&'static str),
    /// A numeric value embedded in the selector could not be parsed.
    /// Carries the original token.
    BadNumber(String),
    /// A key in the selector literal was not one of the recognized
    /// fields. Carries the offending key (owned so callers can pass
    /// dynamic strings).
    UnknownField(String),
}

impl fmt::Display for ParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ParseError::InvalidPrefix(p) => {
                write!(f, "invalid selector prefix: '{}' (expected uia:/cdp:/ocr:)", p)
            }
            ParseError::MissingField(name) => {
                write!(f, "missing required field: {}", name)
            }
            ParseError::BadNumber(tok) => {
                write!(f, "bad number token: '{}'", tok)
            }
            ParseError::UnknownField(k) => {
                write!(f, "unknown selector field: '{}'", k)
            }
        }
    }
}

impl std::error::Error for ParseError {}
