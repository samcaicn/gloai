// Fix workflow files via GitHub Contents API
const fs = require('fs');
const { execSync } = require('child_process');

const REPO = 'samcaicn/gloai';
const BRANCH = 'v2';

function ghApi(method, endpoint, body) {
  const args = ['gh', 'api', '--method', method, `repos/${REPO}/${endpoint}`];
  if (body) {
    args.push('--field', 'message=' + body.message);
    if (body.content) args.push('--field', 'content=' + body.content);
    if (body.sha) args.push('--field', 'sha=' + body.sha);
    if (body.branch) args.push('--field', 'branch=' + body.branch);
  }
  const result = execSync(args.join(' '), { encoding: 'utf8', maxBuffer: 10*1024*1024, input: '' });
  return JSON.parse(result);
}

function ghApiRaw(method, endpoint, input) {
  const args = ['gh', 'api', '--method', method, `-H "Accept: application/vnd.github+json"`];
  // Use --raw-field for proper encoding
  for (const [k, v] of Object.entries(input || {})) {
    args.push('--raw-field', `${k}=${v}`);
  }
  args.push(`repos/${REPO}/${endpoint}`);
  const result = execSync(args.join(' '), { encoding: 'utf8', maxBuffer: 10*1024*1024 });
  return JSON.parse(result);
}

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
let buildSha, ciValidateSha;
try {
  const buildResp = JSON.parse(execSync(`gh api repos/${REPO}/contents/.github/workflows/build.yml?ref=${BRANCH} --jq "{sha: .sha}"`, { encoding: 'utf8' }));
  buildSha = buildResp.sha;
  console.log(`  build.yml SHA: ${buildSha}`);
} catch(e) {
  console.log('  build.yml: Could not get SHA, will create new file');
}

try {
  const ciValidateResp = JSON.parse(execSync(`gh api repos/${REPO}/contents/.github/workflows/ci-validate.yml?ref=${BRANCH} --jq "{sha: .sha}"`, { encoding: 'utf8' }));
  ciValidateSha = ciValidateResp.sha;
  console.log(`  ci-validate.yml SHA: ${ciValidateSha}`);
} catch(e) {
  console.log('  ci-validate.yml: Could not get SHA, will create new file');
}

// Update files
console.log('Updating build.yml...');
try {
  const input = { message: 'ci: quote on-key + add cache:pnpm', content: buildB64, branch: BRANCH };
  if (buildSha) input.sha = buildSha;
  const result = ghApiRaw('PUT', 'contents/.github/workflows/build.yml', input);
  console.log('  build.yml updated:', result.commit?.sha);
} catch(e) {
  console.log('  build.yml update failed:', e.message);
}

console.log('Updating ci-validate.yml...');
try {
  const input = { message: 'ci: quote on-key + remove deprecated env', content: ciValidateB64, branch: BRANCH };
  if (ciValidateSha) input.sha = ciValidateSha;
  const result = ghApiRaw('PUT', 'contents/.github/workflows/ci-validate.yml', input);
  console.log('  ci-validate.yml updated:', result.commit?.sha);
} catch(e) {
  console.log('  ci-validate.yml update failed:', e.message);
}

console.log('\nDone!');
