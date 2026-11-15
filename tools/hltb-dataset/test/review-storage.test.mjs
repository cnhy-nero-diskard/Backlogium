import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, link, symlink, rm } from 'node:fs/promises';
import path from 'node:path';
import { TOOL_DIR } from '../refresh-options.mjs';
import { guardReview, guardPaths, createPrivateJson } from '../refresh-storage.mjs';
test('private reviews refuse existing destinations and all protected path/inode aliases', async (t) => {
  const local = path.join(TOOL_DIR, '.local'); await mkdir(local, { recursive: true });
  const dir = await mkdtemp(path.join(local, 'review-test-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const source = path.join(dir, 'source.json'); await writeFile(source, 'original');
  const stateDir = path.join(dir, 'state'); await mkdir(path.join(stateDir, 'runs'), { recursive: true });
  const checkpoint = path.join(stateDir, 'runs', 'run.json'); await writeFile(checkpoint, 'checkpoint');
  const review = path.join(dir, 'discovery', 'review.json');
  const config = { source, stateDir, output: true };
  await guardReview(review, config); await createPrivateJson(review, { decision: 'approved' });
  const text = await readFile(review, 'utf8');
  await assert.rejects(guardReview(review, config), /review-already-exists/);
  await assert.rejects(createPrivateJson(review, {}), { code: 'EEXIST' });
  assert.equal(await readFile(review, 'utf8'), text);
  for (const protectedFile of [source, checkpoint]) {
    for (const kind of ['hard', 'symbolic']) {
      const alias = path.join(dir, `${path.basename(protectedFile)}-${kind}`);
      await (kind === 'hard' ? link(protectedFile, alias) : symlink(protectedFile, alias));
      await assert.rejects(guardReview(alias, { source, stateDir }), /review-(path-alias|state-overlap)/);
    }
  }
  await assert.rejects(guardReview('/tmp/review.json', config), /review-must-be-private/);
  const escape = path.join(dir, 'escape'); await symlink('/tmp', escape);
  await assert.rejects(guardReview(path.join(escape, 'review.json'), config), /review-must-be-private/);
  const history = path.join(stateDir, 'history.json'); await writeFile(history, 'history');
  const alias = path.join(dir, 'history-alias'); await link(history, alias);
  await assert.rejects(guardReview(alias, { source, stateDir }), /review-path-alias/);
  const options = { stateDir: local, output: path.join(dir, 'candidate.json'), report: path.join(dir, 'report.json'), reviewCandidates: review };
  await guardPaths(options, path.join(TOOL_DIR, 'dataset.json'));
  await assert.rejects(guardReview(path.join(TOOL_DIR, 'README.md'), config), /review-must-be-private/);
  assert.equal(await readFile(source, 'utf8'), 'original'); assert.equal(await readFile(checkpoint, 'utf8'), 'checkpoint');
});
test('forced tracked private files and nested state aliases remain protected', async t => {
  const { execFile } = await import('node:child_process'); const { promisify } = await import('node:util');
  const local = path.join(TOOL_DIR, '.local'); await mkdir(local, { recursive: true });
  const dir = await mkdtemp(path.join(local, 'guard-test-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const tracked = path.join(dir, 'tracked.json'); await writeFile(tracked, '{}');
  const previous = process.env.GIT_INDEX_FILE;
  process.env.GIT_INDEX_FILE = path.join(dir, 'isolated-index');
  try {
    await promisify(execFile)('git', ['add', '-f', '--', tracked], { cwd: TOOL_DIR, env: process.env });
    await assert.rejects(guardReview(tracked), /review-must-be-untracked/);
  } finally { if (previous === undefined) delete process.env.GIT_INDEX_FILE; else process.env.GIT_INDEX_FILE = previous; }
  const stateDir = path.join(dir, 'nested-state'); await mkdir(path.join(stateDir, 'runs'), { recursive: true });
  const checkpoint = path.join(stateDir, 'runs', 'checkpoint.json'); await writeFile(checkpoint, 'checkpoint');
  const alias = path.join(dir, 'alias.json'); await link(checkpoint, alias);
  await assert.rejects(guardReview(alias), /review-path-alias/);
  await assert.rejects(guardReview(path.join(stateDir, 'runs', 'future.json')), /review-path-alias/);
  const reportAlias = path.join(dir, 'report-alias.json'); await link(checkpoint, reportAlias);
  await assert.rejects(guardPaths({ stateDir, output: path.join(dir, 'candidate.json'), report: reportAlias }, path.join(TOOL_DIR, 'dataset.json')), /output-path-alias/);
  assert.equal(await readFile(checkpoint, 'utf8'), 'checkpoint');
});
