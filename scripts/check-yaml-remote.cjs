const fs = require('fs');
const path = require('path');
const os = require('os');

const remoteFile = path.join(os.tmpdir(), 'remote_build_check.yml');
try {
  const content = fs.readFileSync(remoteFile, 'utf8');
  // Try to parse as YAML
  try {
    const yaml = require('js-yaml');
    const doc = yaml.load(content);
    const jobs = Object.keys(doc.jobs || {});
    console.log('YAML OK, jobs:', jobs.join(', '));
    // Check for any job with issues
    for (const [name, job] of Object.entries(doc.jobs || {})) {
      if (job.needs) {
        const needs = Array.isArray(job.needs) ? job.needs : [job.needs];
        for (const n of needs) {
          if (!doc.jobs[n]) {
            console.log('ERROR: job "' + name + '" needs "' + n + '" but it does not exist!');
          }
        }
      }
    }
  } catch(e) {
    console.log('YAML PARSE ERROR:', e.message);
  }
} catch(e) {
  console.log('FILE ERROR:', e.message);
}
