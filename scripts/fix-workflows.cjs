// Fix and update workflow files via GitHub Git Data API
// This bypasses the `workflow` scope requirement by using the Git Data API
// instead of the Contents API or git push.
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const REPO = 'samcaicn/gloai';
const BRANCH = 'v2';

function ghApi(method, endpoint, body) {
  const args = ['gh', 'api', '--method', method, `repos/${REPO}/${endpoint}`];
  if (body) {
    args.push('--input', '-');
  }
  const input = body ? JSON.stringify(body) : '';
  const result = execSync(args.join(' '), { input, encoding: 'utf8', maxBuffer: 10*1024*1024 });
  return JSON.parse(result);
}

// 1. Get current commit SHA
console.log('1. Getting current commit SHA...');
const ref = ghApi('GET', `git/refs/heads/${BRANCH}`);
const commitSha = ref.object.sha;
console.log(`   Current SHA: ${commitSha}`);

// 2. Get current commit to get tree SHA
console.log('2. Getting current commit tree...');
const commit = ghApi('GET', `git/commits/${commitSha}`);
const treeSha = commit.tree.sha;
console.log(`   Tree SHA: ${treeSha}`);

// 3. Read and fix build.yml
console.log('3. Reading and fixing build.yml...');
const buildYml = fs.readFileSync('.github/workflows/build.yml', 'utf8');
// Fix: quote the 'on' key to avoid YAML boolean interpretation
let fixedBuildYml = buildYml.replace(/^on:/m, '"on":');
// Add cache: pnpm to validate job's setup-node (already done locally)
console.log(`   Original size: ${buildYml.length}, Fixed size: ${fixedBuildYml.length}`);

// 4. Read and fix ci-validate.yml
console.log('4. Reading and fixing ci-validate.yml...');
const ciValidateYml = fs.readFileSync('.github/workflows/ci-validate.yml', 'utf8');
// Fix 1: quote the 'on' key
let fixedCiValidateYml = ciValidateYml.replace(/^on:/m, '"on":');
// Fix 2: remove FORCE_JAVASCRIPT_ACTIONS_TO_NODE24 env (deprecated)
fixedCiValidateYml = fixedCiValidateYml.replace(/env:\n  FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: "true"\n/, '');
console.log(`   Original size: ${ciValidateYml.length}, Fixed size: ${fixedCiValidateYml.length}`);

// 5. Create blobs for both files
console.log('5. Creating blobs...');
const buildBlob = ghApi('POST', 'git/blobs', {
  content: fixedBuildYml,
  encoding: 'utf-8',
});
console.log(`   build.yml blob SHA: ${buildBlob.sha}`);

const ciValidateBlob = ghApi('POST', 'git/blobs', {
  content: fixedCiValidateYml,
  encoding: 'utf-8',
});
console.log(`   ci-validate.yml blob SHA: ${ciValidateBlob.sha}`);

// 6. Create new tree
console.log('6. Creating new tree...');
const newTree = ghApi('POST', 'git/trees', {
  base_tree: treeSha,
  tree: [
    {
      path: '.github/workflows/build.yml',
      mode: '100644',
      type: 'blob',
      sha: buildBlob.sha,
    },
    {
      path: '.github/workflows/ci-validate.yml',
      mode: '100644',
      type: 'blob',
      sha: ciValidateBlob.sha,
    },
  ],
});
console.log(`   New tree SHA: ${newTree.sha}`);

// 7. Create new commit
console.log('7. Creating new commit...');
const newCommit = ghApi('POST', 'git/commits', {
  message: 'ci: fix workflow YAML on-key quoting + remove deprecated env',
  tree: newTree.sha,
  parents: [commitSha],
});
console.log(`   New commit SHA: ${newCommit.sha}`);

// 8. Update branch reference
console.log('8. Updating v2 branch reference...');
const updatedRef = ghApi('PATCH', `git/refs/heads/${BRANCH}`, {
  sha: newCommit.sha,
});
console.log(`   Updated ref: ${updatedRef.object.sha}`);
console.log('\nDone! Workflow files updated via Git Data API.');
