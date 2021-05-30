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
pub extern fn handle_http_request(ctx_raw: *mut c_char) -> *mut c_char {    
    let (c_string, _bytes) = unsafe { 
        let bytes = CStr::from_ptr(ctx_raw).to_bytes();
        let filtered_bytes = &bytes[0..bytes.len() - 0];
        // match base64::decode(filtered_bytes) {
        //     Err(why) => {
        //         let why_str = why.to_string();
        //         let formatted = format!(
        //             r#"{{ "err": "err_from_ptr", "err_desc": "{}" }}"#,
        //             why_str,
        //         );
        //         (formatted, bytes)
        //     },
        //     Ok(res) => {
        //         let arr = &res[..];
        //         let s: &str = str::from_utf8(arr).unwrap();
        //         (String::from(s), bytes)
        //     }
        // }
        match str::from_utf8(filtered_bytes) {
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
            unsafe { CString::from_vec_unchecked(formatted.as_str().as_bytes().to_vec()) }.into_raw()
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
                    let response_b = response.to_string().as_bytes().to_vec();
                    unsafe { CString::from_vec_unchecked(response_b.to_vec()) }.into_raw()
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
                    let response_b = response.to_string().as_bytes().to_vec();
                    unsafe { CString::from_vec_unchecked(response_b.to_vec()) }.into_raw()
                }
            }
        }
    }
}