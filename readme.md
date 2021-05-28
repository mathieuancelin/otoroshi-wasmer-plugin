# otoroshi-wasmer-plugin

an otoroshi plugin to respond to an http call from a WASM script. 
very rought, ugly, underperformant and unstable ;)

## build the otoroshi plugin

```sh
sbt package
```

## run otoroshi with the plugin

```sh
wget https://github.com/MAIF/otoroshi/releases/download/v1.5.0-alpha.14/otoroshi.jar
wget https://github.com/wasmerio/wasmer-java/releases/download/0.3.0/wasmer-jni-amd64-darwin-0.3.0.jar
# or wget https://github.com/wasmerio/wasmer-java/releases/download/0.3.0/wasmer-jni-amd64-linux-0.3.0.jar
# or wget https://github.com/wasmerio/wasmer-java/releases/download/0.3.0/wasmer-jni-amd64-windows-0.3.0.jar
java -cp "./wasmer-jni-amd64-darwin-0.3.0.jar:./target/scala-2.12/otorshi-wasmer-plugin_2.12-1.0.0-dev.jar:./otoroshi.jar" play.core.server.ProdServerStart
```

then log into otoroshi, creates a new service and add the plugin in the transformer section and configure it like

```json
{
  "WasmerResponse": {
    "pages": 1,
    "wasm": "https://url.to.your/compiled.wasm.script"
  }
}
```

## make your own webassembly plugin

```toml
[package]
name = "oto-plugin-wasm"
version = "0.1.0"
edition = "2018"

[lib]
crate-type = ["cdylib"]

[dependencies]
wasm-bindgen = "0.2"
json = "0.12.4"
```

```rust
use std::ffi::{CStr, CString};
use std::mem;
use std::os::raw::{c_char, c_void};
use std::str;

extern crate json;

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
```

build with 

```sh
cargo install wasm-pack
wasm-pack build --target web
```