
//
// SSH tunnel manager. The TypeScript module wrapped `ssh2` to expose
// port-forwarding commands; the Rust port exposes the same data
// structures and a stub `open()` that the main thread can implement
// using `tokio::process::Command` + `ssh`/`sshd` or a future
// `russh` integration.

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct SshTunnelSpec {
    pub local_port: u16,
    pub remote_host: String,
    pub remote_port: u16,
    #[serde(default)]
    pub identity_file: Option<String>,
    #[serde(default)]
    pub user: Option<String>,
    #[serde(default)]
    pub jump_host: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct SshTunnelStatus {
    pub id: String,
    pub spec: SshTunnelSpec,
    pub running: bool,
    pub last_error: Option<String>,
    pub bytes_in: u64,
    pub bytes_out: u64,
}

#[derive(Default)]
pub struct SshTunnelManager {
    tunnels: Vec<SshTunnelStatus>,
}


impl SshTunnelManager {
    pub fn new() -> Self { Self::default() }

    pub fn open(&mut self, id: String, spec: SshTunnelSpec) -> Result<String, String> {
        // Real implementation will spawn `ssh -N -L ...`. For now we record
        // the spec and return a synthetic handle so the front-end can wire
        // the rest of the UX.
        if let Some(s) = self.tunnels.iter_mut().find(|t| t.id == id) {
            s.spec = spec;
            s.running = true;
            return Ok(id);
        }
        self.tunnels.push(SshTunnelStatus { id: id.clone(), spec, running: true, ..Default::default() });
        Ok(id)
    }

    pub fn close(&mut self, id: &str) -> bool {
        if let Some(t) = self.tunnels.iter_mut().find(|t| t.id == id) { t.running = false; true } else { false }
    }

    pub fn list(&self) -> Vec<SshTunnelStatus> { self.tunnels.clone() }

    pub fn status(&self, id: &str) -> Option<SshTunnelStatus> {
        self.tunnels.iter().find(|t| t.id == id).cloned()
    }
}
