//! Nachgebaute TRS API + Mojang für die Tests: ein kleiner HTTP-Server, der
//! jede Anfrage an eine Routing-Funktion gibt und alle Anfragen mitschreibt.

use std::sync::{Arc, Mutex};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

#[derive(Debug, Clone)]
pub struct Request {
    pub method: String,
    pub path: String,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Request {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(n, _)| n.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }

    pub fn json(&self) -> serde_json::Value {
        serde_json::from_slice(&self.body).unwrap_or(serde_json::Value::Null)
    }

    pub fn bearer(&self) -> Option<&str> {
        self.header("authorization").and_then(|v| v.strip_prefix("Bearer "))
    }
}

pub struct Response {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Response {
    pub fn json(status: u16, value: serde_json::Value) -> Self {
        Self {
            status,
            headers: vec![("content-type".into(), "application/json".into())],
            body: serde_json::to_vec(&value).unwrap(),
        }
    }

    pub fn empty(status: u16) -> Self {
        Self { status, headers: Vec::new(), body: Vec::new() }
    }

    pub fn error(status: u16, code: &str) -> Self {
        Self::json(status, serde_json::json!({ "error": { "code": code, "message": code } }))
    }

    pub fn png(bytes: Vec<u8>) -> Self {
        Self { status: 200, headers: vec![("content-type".into(), "image/png".into())], body: bytes }
    }

    pub fn with_header(mut self, name: &str, value: &str) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }
}

type Handler = Arc<dyn Fn(&Request) -> Response + Send + Sync>;

pub struct MockServer {
    pub base: String,
    pub requests: Arc<Mutex<Vec<Request>>>,
    task: tokio::task::JoinHandle<()>,
}

impl Drop for MockServer {
    fn drop(&mut self) {
        self.task.abort();
    }
}

impl MockServer {
    pub async fn start(handler: impl Fn(&Request) -> Response + Send + Sync + 'static) -> Self {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let base = format!("http://127.0.0.1:{}", listener.local_addr().unwrap().port());
        let requests = Arc::new(Mutex::new(Vec::new()));
        let handler: Handler = Arc::new(handler);
        let log = Arc::clone(&requests);
        let task = tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else { return };
                let handler = Arc::clone(&handler);
                let log = Arc::clone(&log);
                tokio::spawn(async move {
                    let Some(request) = read_request(&mut stream).await else { return };
                    let response = handler(&request);
                    log.lock().unwrap().push(request);
                    let mut head = format!("HTTP/1.1 {} X\r\n", response.status);
                    for (name, value) in &response.headers {
                        head.push_str(&format!("{name}: {value}\r\n"));
                    }
                    head.push_str(&format!("content-length: {}\r\nconnection: close\r\n\r\n", response.body.len()));
                    let _ = stream.write_all(head.as_bytes()).await;
                    let _ = stream.write_all(&response.body).await;
                    let _ = stream.flush().await;
                });
            }
        });
        Self { base, requests, task }
    }

    pub fn requests(&self) -> Vec<Request> {
        self.requests.lock().unwrap().clone()
    }

    /// Anfragen an einen Pfad (ohne Query).
    pub fn hits(&self, method: &str, path: &str) -> Vec<Request> {
        self.requests()
            .into_iter()
            .filter(|r| r.method == method && r.path.split('?').next() == Some(path))
            .collect()
    }
}

async fn read_request(stream: &mut tokio::net::TcpStream) -> Option<Request> {
    let mut data = Vec::new();
    let mut buf = [0u8; 8192];
    let head_end = loop {
        let n = stream.read(&mut buf).await.ok()?;
        if n == 0 {
            return None;
        }
        data.extend_from_slice(&buf[..n]);
        if let Some(pos) = data.windows(4).position(|w| w == b"\r\n\r\n") {
            break pos;
        }
    };
    let head = String::from_utf8_lossy(&data[..head_end]).into_owned();
    let mut lines = head.lines();
    let mut first = lines.next()?.split_whitespace();
    let method = first.next()?.to_owned();
    let path = first.next()?.to_owned();
    let headers: Vec<(String, String)> = lines
        .filter_map(|l| l.split_once(':').map(|(n, v)| (n.trim().to_ascii_lowercase(), v.trim().to_owned())))
        .collect();
    let length: usize =
        headers.iter().find(|(n, _)| n == "content-length").and_then(|(_, v)| v.parse().ok()).unwrap_or(0);
    let mut body = data[head_end + 4..].to_vec();
    while body.len() < length {
        let n = stream.read(&mut buf).await.ok()?;
        if n == 0 {
            break;
        }
        body.extend_from_slice(&buf[..n]);
    }
    Some(Request { method, path, headers, body })
}
