// Fix workflow files via GitHub Contents API using stdin
const fs = require('fs');
const { execSync, execFileSync } = require('child_process');

const REPO = 'samcaicn/gloai';
const BRANCH = 'v2';

// Read and fix build.yml
console.log('Reading and fixing build.yml...');
const buildYml = fs.readFileSync('.github/workflows/build.yml', 'utf8');
let fixedBuildYml = buildYml.replace(/^on:/m, '"on":');

// Read and fix ci-validate.yml
console.log('Reading and fixing ci-validate.yml...');
const ciValidateYml = fs.readFileSync('.github/workflows/ci-validate.yml', 'utf8');
let fixedCiValidateYml = ciValidateYml.replace(/^on:/m, '"on":');
fixedCiValidateYml = fixedCiValidateYml.replace(/env:\n  FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: "true"\n/, '');

// Base64 encode
const buildB64 = Buffer.from(fixedBuildYml).toString('base64');
const ciValidateB64 = Buffer.from(fixedCiValidateYml).toString('base64');

// Get current file SHAs
console.log('Getting current file SHAs...');
let buildSha = null, ciValidateSha = null;
try {
  const buildResp = JSON.parse(execSync(`gh api repos/${REPO}/contents/.github/workflows/build.yml?ref=${BRANCH}`, { encoding: 'utf8', maxBuffer: 10*1024*1024 }));
  buildSha = buildResp.sha;
  console.log(`  build.yml SHA: ${buildSha}`);
} catch(e) {
  console.log('  build.yml: Could not get SHA');
}

try {
  const ciValidateResp = JSON.parse(execSync(`gh api repos/${REPO}/contents/.github/workflows/ci-validate.yml?ref=${BRANCH}`, { encoding: 'utf8', maxBuffer: 10*1024*1024 }));
  ciValidateSha = ciValidateResp.sha;
  console.log(`  ci-validate.yml SHA: ${ciValidateSha}`);
} catch(e) {
  console.log('  ci-validate.yml: Could not get SHA');
}

// Write JSON payloads to temp files
const buildPayload = JSON.stringify({
  message: 'ci: quote on-key + add cache:pnpm for validate job',
  content: buildB64,
  branch: BRANCH,
  ...(buildSha ? { sha: buildSha } : {}),
});

const ciValidatePayload = JSON.stringify({
  message: 'ci: quote on-key + remove deprecated FORCE_JAVASCRIPT_ACTIONS_TO_NODE24 env',
  content: ciValidateB64,
  branch: BRANCH,
  ...(ciValidateSha ? { sha: ciValidateSha } : {}),
});

fs.writeFileSync('_build_payload.json', buildPayload);
fs.writeFileSync('_civalidate_payload.json', ciValidatePayload);

// Update files using --input with stdin
console.log('Updating build.yml...');
try {
  const result = execSync(
    `gh api --method PUT repos/${REPO}/contents/.github/workflows/build.yml --input _build_payload.json`,
    { encoding: 'utf8', maxBuffer: 10*1024*1024 }
  );
  const parsed = JSON.parse(result);
  console.log('  build.yml updated! Commit:', parsed.commit?.sha);
} catch(e) {
  console.log('  build.yml update failed:', e.message?.substring(0, 200));
}

console.log('Updating ci-validate.yml...');
try {
  const result = execSync(
    `gh api --method PUT repos/${REPO}/contents/.github/workflows/ci-validate.yml --input _civalidate_payload.json`,
    { encoding: 'utf8', maxBuffer: 10*1024*1024 }
  );
  const parsed = JSON.parse(result);
  console.log('  ci-validate.yml updated! Commit:', parsed.commit?.sha);
} catch(e) {
  console.log('  ci-validate.yml update failed:', e.message?.substring(0, 200));
}

// Cleanup
try { fs.unlinkSync('_build_payload.json'); } catch {}
try { fs.unlinkSync('_civalidate_payload.json'); } catch {}

console.log('\nDone!');
