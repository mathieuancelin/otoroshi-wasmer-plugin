use std::ffi::{CStr, CString};
use std::mem;
use std::os::raw::{c_char, c_void};
use std::str;

extern crate json;
extern crate base64;

#[no_mangle]
pub extern fn allocate(size: usize) -> *mut c_void {
    let mut buffer = Vec::with_capacity(size);
    let pointer = buffer.as_mut_ptr();
    mem::forget(buffer);

    pointer as *mut c_void
}

#[no_mangle]
pub extern fn deallocate(pointer: *mut c_void, capacity: usize) {
    unsafe {
        let _ = Vec::from_raw_parts(pointer, 0, capacity);
    }
}

#[no_mangle]
pub extern fn handle_http_request(pointer: *mut u8, length: u32) -> *const u8 {    
    let (c_string, _bytes) = unsafe { 
        let bytes: &mut [u8] = core::slice::from_raw_parts_mut(pointer, length as usize);
        match str::from_utf8_mut(bytes) {
            Err(why) => {
                let why_str = why.to_string();
                let formatted = format!(
                    r#"{{ "err": "err_from_ptr", "err_desc": "{}" }}"#,
                    why_str,
                );
                (formatted, bytes)
            },
            Ok(res) => (String::from(res), bytes),
        }
    };
    match json::parse(c_string.as_str()) {
        Err(why) => {
            let why_str = why.to_string();
            let formatted = format!(
                r#"{{ "err": "err_json_parse", "err_desc": "{}" }}"#,
                why_str,
            );
            formatted.as_bytes().as_ptr()
        },
        Ok(ctx) => {
            match ctx["err"].as_str() {
                None => {
                    let method = ctx["method"].as_str().unwrap_or("--");
                    let path = ctx["path"].as_str().unwrap_or("--");
                    let host = ctx["headers"]["Host"].as_str().unwrap_or("--");
                    let response = match (method, path) {
                        ("GET", "/") => {
                            json::object!{
                                "status": 200,
                                "body": format!("<h1>Hello from {}!</h1>", host),
                                "headers": {
                                    "Content-Type": "text/html; charset=utf-8",
                                }
                            }
                        },
                        _ => {
                            json::object!{
                                "status": 404,
                                "body": {
                                    "err": "resource not found",
                                },
                                "headers": {
                                    "Content-Type": "application/json",
                                }
                            }
                        },
                    };
                    response.to_string().as_bytes().as_ptr()
                },
                Some(err) => {
                    let err_desc = ctx["err_desc"].as_str().unwrap_or("--");
                    let response = json::object!{
                        "status": 500,
                        "headers": {},
                        "body": {
                            "err": err,
                            "err_desc": err_desc
                        }
                    };
                    response.to_string().as_bytes().as_ptr()
                }
            }
        }
    }
}