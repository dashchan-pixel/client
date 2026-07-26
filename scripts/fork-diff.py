#!/usr/bin/env python3
"""Check a fork of this app for fixes worth porting back.

Usage:
	scripts/fork-diff.py <fork>                 directory, .zip/.tar.gz, git URL, owner/repo
	scripts/fork-diff.py <fork> --out DIR       also write REPORT.md + per-file diffs
	scripts/fork-diff.py <fork> --all           keep the low-signal diffs too
	scripts/fork-diff.py <fork> --filter http   only paths containing this substring

A fork of this project is usually a *snapshot*: an unpacked release zip, a
vendored copy, or a clone whose history was squashed or rewritten. So nothing
here needs the fork to have commits. What it diffs against is OUR object
store, which still holds every Java-era blob from before the J2K conversion:

  1. Every blob reachable from any of our refs is indexed by id. A fork file
     whose content equals one of them is untouched upstream work and is
     dropped. That single filter is what keeps the report short.
  2. What survives is diffed against the *most similar* revision that path
     ever had here -- not the newest, the closest. For a Java-era fork that
     lands on the last .java before the conversion deleted it, so the diff is
     the fork's own edit and not 40k lines of Java-vs-Kotlin noise.
  3. Each diff is scored on what tends to actually be worth porting: changed
     endpoints and regexes, added null guards and catch blocks, captcha /
     Cloudflare handling, and commit-message-grade words like "fix" or "crash".

If the fork does share history with us, commits without an equivalent patch in
the current branch are listed too (patch-id matched, so anything already
cherry-picked is filtered out). That step fetches the fork into `refs/fork-check/`
here and deletes the ref again on the way out -- the objects it downloaded stay
until the next gc. Pass --no-commits to skip it entirely.

Why not `git log --raw` to find each path's revisions: this repo's Java
history arrived through merge commits, whose diffs `git log` hides by default,
and history simplification drops the rest. Enumerating reachable objects is
the only listing that sees all of it.

Nothing is applied automatically. A Java hunk is a hint about WHAT to change,
not a patch -- the target file is Kotlin now, and AGENTS.md's rules about null
handling apply when porting it (do not let a Java null check become `!!`).
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from collections import Counter
from difflib import unified_diff

# Present in a checkout, but says nothing about what the fork changed.
SKIP_DIRS = {'.git', '.gradle', '.idea', '.kotlin', '.claude', 'build', 'out', 'bin',
	'gen', 'node_modules', 'captures', '.externalNativeBuild', '.cxx'}
SKIP_EXTS = {'.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.ico', '.ttf', '.otf',
	'.jar', '.aar', '.so', '.apk', '.dex', '.zip', '.gz', '.7z', '.keystore', '.jks',
	'.class', '.bin', '.pdf', '.mp4', '.webm', '.ogg', '.wav', '.iml'}
MAX_FILE_BYTES = 1 << 20
# Revisions of one path to consider before picking the closest. PostsPage.java has 84.
MAX_REVISIONS = 12

# Where a fork's layout can sit relative to ours. All matches are tried, best first.
PREFIX_REWRITES = [
	('src/main/java/', 'src/'),
	('src/main/kotlin/', 'src/'),
	('src/main/res/', 'res/'),
	('src/main/AndroidManifest.xml', 'AndroidManifest.xml'),
	('app/src/main/java/', 'src/'),
	('app/src/main/kotlin/', 'src/'),
	('app/src/main/res/', 'res/'),
	('client/', ''),
	('app/', ''),
]
# A directory is the fork's client root if it holds one of these.
ANCHORS = ['src/com/mishiranu', 'src/chan/content', 'src/main/java/com/mishiranu',
	'app/src/main/java/com/mishiranu']

# Scored against changed lines. A line can hit several; the labels are shown in the report.
HIGH = [
	('null-guard', r'==\s*null|!=\s*null|\?:|\?\.|isNullOrEmpty|requireNotNull|requireNonNull'),
	('error-handling', r'\bcatch\s*\(|\btry\s*[{(]|\bthrow\b|NullPointer|IllegalState|IndexOutOfBounds|OutOfMemory|ArrayIndexOut'),
	('endpoint', r'https?://|\b\w+\.(?:php|json|cgi|aspx)\b|/api/|\.onion\b'),
	('parsing', r'Pattern\.compile|Regex\(|\.toRegex\(|\.matcher\(|JSONObject|JsonSerial|substring\('),
	('anti-bot', r'(?i)captcha|cloudflare|firewall|passcode|user-?agent|referer|cf_clearance|\bcookie'),
]
MED = [
	('fix-vocab', r'(?i)\b(fix(e[sd])?|bug|crash(e[sd])?|npe|workaround|hotfix|regression|broken|leak|deadlock|race\s+condition)\b'),
	('version/dep', r'versionCode|versionName|minSdk|targetSdk|implementation[\s(]|\bapi\s*[("\']'),
	('resource', r'<string\s+name=|<bool\s+name=|<integer\s+name=|<style\s+name='),
]
# Churn in every fork ever: imports, lone braces, blank lines, bare comment lines.
NOISE_LINE = re.compile(r'^\s*(?:import\s|package\s|//|\*|/\*|\*/|[{}()\[\];,]*\s*)$')

HIGH = [(n, re.compile(p)) for n, p in HIGH]
MED = [(n, re.compile(p)) for n, p in MED]


def git(root, *args, check=True):
	r = subprocess.run(['git', *args], cwd=root, stdout=subprocess.PIPE,
		stderr=subprocess.PIPE, check=False)
	if check and r.returncode != 0:
		raise SystemExit('git %s failed: %s' % (' '.join(args),
			r.stderr.decode('utf-8', 'replace').strip()))
	return r.stdout.decode('utf-8', 'replace')


def blob_id(data, algo):
	h = algo()
	h.update(b'blob %d\0' % len(data))
	h.update(data)
	return h.hexdigest()


def decode(data):
	for enc in ('utf-8', 'latin-1'):
		try:
			return data.decode(enc)
		except UnicodeDecodeError:
			continue
	return data.decode('utf-8', 'replace')


def normalize(text):
	"""Line endings and trailing whitespace are not a fix."""
	return '\n'.join(l.rstrip() for l in text.replace('\r\n', '\n').split('\n')).strip()


# ------------------------------------------------------------ our object store

def build_index(root):
	"""Every blob we can reach, and every path any of them ever sat at.

	`git rev-list --objects` names trees as well as blobs; the type is settled
	later by batch-check, where the sizes are being read anyway.
	"""
	blobs, paths = set(), {}
	for line in git(root, 'rev-list', '--objects', '--all').splitlines():
		oid, _, path = line.partition(' ')
		blobs.add(oid)
		if path:
			paths.setdefault(path, []).append(oid)
	head = set(git(root, 'ls-files').splitlines())
	by_name = {}
	for path in paths:
		by_name.setdefault(os.path.basename(path), []).append(path)
	return blobs, paths, head, by_name


def batch_check(root, oids):
	"""oid -> (type, size), one subprocess for the lot."""
	if not oids:
		return {}
	p = subprocess.Popen(['git', 'cat-file', '--batch-check'], cwd=root,
		stdin=subprocess.PIPE, stdout=subprocess.PIPE)
	out, _ = p.communicate(('\n'.join(oids) + '\n').encode())
	info = {}
	for line in out.decode('utf-8', 'replace').splitlines():
		fields = line.split()
		if len(fields) == 3:
			info[fields[0]] = (fields[1], int(fields[2]))
	return info


def batch_cat(root, oids):
	"""oid -> content, one subprocess for the lot."""
	oids = list(oids)
	if not oids:
		return {}
	p = subprocess.Popen(['git', 'cat-file', '--batch'], cwd=root,
		stdin=subprocess.PIPE, stdout=subprocess.PIPE)
	out, _ = p.communicate(('\n'.join(oids) + '\n').encode())
	result, pos = {}, 0
	while pos < len(out):
		nl = out.find(b'\n', pos)
		if nl < 0:
			break
		header = out[pos:nl].split()
		if len(header) < 3:  # "<oid> missing"
			pos = nl + 1
			continue
		size = int(header[2])
		result[header[0].decode()] = out[nl + 1:nl + 1 + size]
		pos = nl + 1 + size + 1
	return result


# --------------------------------------------------------------------- the fork

def materialize(spec, tmp):
	"""Accept a directory, an archive, or something clonable. -> (path, kind)."""
	if os.path.isdir(spec):
		return os.path.abspath(spec), 'directory'
	if os.path.isfile(spec):
		dest = os.path.join(tmp, 'extract')
		os.makedirs(dest)
		if zipfile.is_zipfile(spec):
			with zipfile.ZipFile(spec) as z:
				z.extractall(dest)
		elif tarfile.is_tarfile(spec):
			with tarfile.open(spec) as t:
				t.extractall(dest, filter='data')
		else:
			raise SystemExit('%s is neither a zip nor a tar archive' % spec)
		entries = [e for e in os.listdir(dest) if not e.startswith('.')]
		if len(entries) == 1 and os.path.isdir(os.path.join(dest, entries[0])):
			dest = os.path.join(dest, entries[0])  # archives wrap everything in one folder
		return dest, 'archive'
	if re.match(r'^(https?://|git@|ssh://)', spec):
		url = spec
	elif re.match(r'^[\w.-]+/[\w.-]+$', spec):
		url = 'https://github.com/%s' % spec
	else:
		raise SystemExit('no such fork: %s' % spec)
	dest = os.path.join(tmp, 'clone')
	print('cloning %s ...' % url, file=sys.stderr)
	subprocess.run(['git', 'clone', '--quiet', url, dest], check=True)
	return dest, 'clone'


def find_root(base, depth=3):
	"""The fork may nest the client under client/, app/, or a release folder."""
	level, seen = [base], []
	for _ in range(depth):
		seen += level
		nxt = []
		for d in level:
			try:
				nxt += [os.path.join(d, e) for e in sorted(os.listdir(d))
					if e not in SKIP_DIRS and os.path.isdir(os.path.join(d, e))]
			except OSError:
				pass
		level = nxt
	for d in seen + level:
		if any(os.path.exists(os.path.join(d, a)) for a in ANCHORS):
			return d
	return base


def walk(root):
	for dirpath, dirnames, filenames in os.walk(root):
		dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
		for name in filenames:
			full = os.path.join(dirpath, name)
			if os.path.splitext(name)[1].lower() in SKIP_EXTS or os.path.islink(full):
				continue
			try:
				if os.path.getsize(full) > MAX_FILE_BYTES:
					continue
			except OSError:
				continue
			yield full, os.path.relpath(full, root).replace(os.sep, '/')


def our_paths_for(rel):
	"""Every path of ours the fork's `rel` could correspond to, best first."""
	forms = [rel]
	for src, dst in PREFIX_REWRITES:
		if rel.startswith(src):
			forms.append(dst + rel[len(src):])
	out = []
	for form in forms:
		out.append(form)
		stem, ext = os.path.splitext(form)
		if ext == '.java':
			out.append(stem + '.kt')
		elif ext == '.kt':
			out.append(stem + '.java')
	seen, unique = set(), []
	for p in out:
		if p not in seen:
			seen.add(p)
			unique.append(p)
	return unique


def pick_revision(fork_text, revisions):
	"""The closest revision we ever had, by shared lines -- not the newest one.

	A fork snapshot is branched from some arbitrary point in upstream history,
	and diffing it against our tip would re-report every change made since.
	"""
	fork_lines = Counter(l.strip() for l in fork_text.splitlines() if l.strip())
	best, best_score = None, -1.0
	for oid, data in revisions.items():
		text = decode(data)
		lines = Counter(l.strip() for l in text.splitlines() if l.strip())
		shared = sum((fork_lines & lines).values())
		score = shared / max(1, max(sum(fork_lines.values()), sum(lines.values())))
		if score > best_score:
			best, best_score = (oid, text), score
	return best


# --------------------------------------------------------------------- scoring

def score_diff(diff_lines):
	score, hits, changed = 0, [], 0
	for line in diff_lines:
		if line[:3] in ('+++', '---') or line[:2] == '@@' or line[:1] not in '+-':
			continue
		changed += 1
		body = line[1:]
		if NOISE_LINE.match(body):
			continue
		labels = [n for n, rx in HIGH if rx.search(body)]
		weight = 3 * len(labels)
		med = [n for n, rx in MED if rx.search(body)]
		weight += 2 * len(med)
		labels += med
		score += weight or 1
		if labels:
			hits.append((weight, line.rstrip(), ' '.join(labels)))
	hits.sort(key=lambda h: -h[0])
	return score, hits[:4], changed


# ---------------------------------------------------------------- commit mode

NS = 'refs/fork-check'


def commit_report(root, fork_dir, ref):
	"""Only possible when the fork still shares history with us."""
	if not os.path.exists(os.path.join(fork_dir, '.git')):
		return None
	git(root, 'fetch', '--no-tags', '--quiet', fork_dir, '+%s:%s/head' % (ref, NS), check=False)
	if not git(root, 'rev-parse', '--verify', '--quiet', '%s/head' % NS, check=False).strip():
		return None
	try:
		head = git(root, 'rev-parse', 'HEAD').strip()
		other = git(root, 'rev-parse', '%s/head' % NS).strip()
		base = git(root, 'merge-base', head, other, check=False).strip()
		if not base:
			return None
		rows = []
		for line in git(root, 'cherry', head, other, base).splitlines():
			if not line.startswith('+'):
				continue  # '-' means an equivalent patch is already in our history
			sha = line.split()[1]
			subject = git(root, 'log', '-1', '--format=%s  (%an, %ad)', '--date=short', sha)
			rows.append((sha, subject.strip()))
		return {'base': base[:9], 'ours': head[:9], 'theirs': other[:9], 'commits': rows}
	finally:
		refs = git(root, 'for-each-ref', '--format=%(refname)', NS, check=False).split()
		if refs:
			subprocess.run(['git', 'update-ref', '--stdin'], cwd=root,
				input=''.join('delete %s\n' % r for r in refs).encode(), check=False)


# ------------------------------------------------------------------- reporting

def main():
	ap = argparse.ArgumentParser(description='Find fixes worth porting from a fork of this app.')
	ap.add_argument('fork', help='directory, .zip/.tar.gz snapshot, git URL, or owner/repo')
	ap.add_argument('--out', metavar='DIR', help='write REPORT.md and diffs/ here')
	ap.add_argument('--all', action='store_true', help='keep low-signal diffs too')
	ap.add_argument('--filter', default='', help='only consider paths containing this substring')
	ap.add_argument('--limit', type=int, default=40, help='ranked entries to print (default 40)')
	ap.add_argument('--min-score', type=int, default=6, help='signal threshold (default 6)')
	ap.add_argument('--ref', default='HEAD', help='fork ref to compare when it has commits')
	ap.add_argument('--no-commits', action='store_true',
		help='skip the commit comparison, which has to fetch the fork into this repo')
	ap.add_argument('--context', type=int, default=3, help='diff context lines (default 3)')
	args = ap.parse_args()

	here = os.path.dirname(os.path.abspath(__file__))
	root = git(here, 'rev-parse', '--show-toplevel').strip()
	if not root or not os.path.exists(os.path.join(root, 'AGENTS.md')):
		raise SystemExit('run this from inside the client repo')
	algo = hashlib.sha256 if git(root, 'rev-parse', '--show-object-format').strip() == 'sha256' \
		else hashlib.sha1
	threshold = 0 if args.all else args.min_score

	tmp = tempfile.mkdtemp(prefix='fork-diff-')
	try:
		fork_base, kind = materialize(args.fork, tmp)
		fork_root = find_root(fork_base)
		blobs, paths, head_paths, by_name = build_index(root)

		unchanged = ignored = 0
		pending, news = [], []
		for full, rel in walk(fork_root):
			if args.filter and args.filter not in rel:
				ignored += 1
				continue
			with open(full, 'rb') as f:
				data = f.read()
			if b'\0' in data[:8000]:
				ignored += 1
				continue
			if blob_id(data, algo) in blobs:
				unchanged += 1
				continue
			match = next((p for p in our_paths_for(rel) if p in paths), None)
			if match is None:
				stem = os.path.splitext(os.path.basename(rel))[0]
				pool = by_name.get(os.path.basename(rel)) or \
					by_name.get(stem + '.kt') or by_name.get(stem + '.java') or []
				match = pool[0] if len(pool) == 1 else None
			if match is None:
				news.append(rel)
				continue
			pending.append((rel, match, decode(data), len(data)))

		# Narrow each path's revisions by size before paying to read them.
		wanted = {oid for _, p, _, _ in pending for oid in paths[p]}
		info = batch_check(root, wanted)
		need = set()
		for _, path, _, size in pending:
			revs = [o for o in paths[path] if info.get(o, ('', 0))[0] == 'blob']
			revs.sort(key=lambda o: abs(info[o][1] - size))
			need.update(revs[:MAX_REVISIONS])
		contents = batch_cat(root, need)

		results, cosmetic = [], 0
		for rel, path, fork_text, size in pending:
			revs = [o for o in paths[path] if o in contents]
			revs.sort(key=lambda o: abs(info[o][1] - size))
			picked = pick_revision(fork_text, {o: contents[o] for o in revs[:MAX_REVISIONS]})
			if picked is None:
				news.append(rel)
				continue
			oid, our_text = picked
			if normalize(our_text) == normalize(fork_text):
				cosmetic += 1
				continue
			diff = list(unified_diff(
				our_text.splitlines(), fork_text.splitlines(),
				fromfile='ours/%s @ %s' % (path, oid[:9]), tofile='fork/%s' % rel,
				lineterm='', n=args.context))
			score, hits, changed = score_diff(diff)
			target = path if path in head_paths else None
			if target is None:  # renamed or deleted since -- say where it lives now
				stem = os.path.splitext(path)[0]
				target = next((c for c in (stem + '.kt', stem + '.java') if c in head_paths), None)
			results.append({'rel': rel, 'our': path, 'oid': oid, 'target': target,
				'score': score, 'hits': hits, 'changed': changed, 'diff': diff,
				'port': path.endswith('.java') and bool(target and target.endswith('.kt'))})
		results.sort(key=lambda r: (-r['score'], -r['changed'], r['rel']))

		commits = None if args.no_commits else commit_report(root, fork_base, args.ref)

		out = []
		w = out.append
		w('fork:  %s (%s)' % (args.fork, kind))
		if os.path.abspath(fork_root) != os.path.abspath(fork_base):
			w('root:  %s' % os.path.relpath(fork_root, fork_base))
		w('ours:  %s @ %s  (%s)' % (git(root, 'rev-parse', '--abbrev-ref', 'HEAD').strip(),
			git(root, 'rev-parse', '--short', 'HEAD').strip(), root))
		w('index: %d objects, %d paths ever tracked' % (len(blobs), len(paths)))
		w('')
		w('  %5d  unchanged   byte-identical to something already in our history' % unchanged)
		w('  %5d  cosmetic    differs only in whitespace / line endings' % cosmetic)
		w('  %5d  modified    real content changes' % len(results))
		w('  %5d  fork-only   no counterpart anywhere in our history' % len(news))
		w('  %5d  skipped     binary, oversized, or filtered out' % ignored)
		w('')

		if commits is not None:
			w('## commits not in ours (%s..%s, merge-base %s)'
				% (commits['ours'], commits['theirs'], commits['base']))
			w('')
			for sha, subject in commits['commits'][:args.limit]:
				w('  %s  %s' % (sha[:9], subject))
			if not commits['commits']:
				w('  none -- every fork commit already has an equivalent patch here')
			elif len(commits['commits']) > args.limit:
				w('  ... and %d more' % (len(commits['commits']) - args.limit))
			w('')

		shown = [r for r in results if r['score'] >= threshold]
		w('## ranked candidates (%d of %d scoring >= %d)' % (len(shown), len(results), threshold))
		w('')
		if not shown:
			w('  nothing above the threshold -- rerun with --all to see everything')
		for r in shown[:args.limit]:
			note = ''
			if r['target'] is None:
				note = '   [no longer in our tree]'
			elif r['port']:
				note = '   [java -> kotlin port]'
			w('%5d  %s%s' % (r['score'], r['rel'], note))
			w('       %d changed lines vs %s @ %s' % (r['changed'], r['our'], r['oid'][:9]))
			if r['target'] and r['target'] != r['our']:
				w('       apply to %s' % r['target'])
			for _, line, labels in r['hits']:
				body = line if len(line) <= 92 else line[:89] + '...'
				w('         %-92s  %s' % (body, labels))
			w('')
		if len(shown) > args.limit:
			w('... and %d more above the threshold (raise --limit)' % (len(shown) - args.limit))
			w('')

		if news:
			w('## files only in the fork (%d)' % len(news))
			w('')
			for rel in sorted(news)[:args.limit]:
				w('  %s' % rel)
			if len(news) > args.limit:
				w('  ... and %d more' % (len(news) - args.limit))
			w('')

		report = '\n'.join(out)
		print(report)

		if args.out:
			diffs = os.path.join(args.out, 'diffs')
			os.makedirs(diffs, exist_ok=True)
			written = 0
			for r in shown:
				name = r['rel'].replace('/', '__') + '.diff'
				with open(os.path.join(diffs, name), 'w') as f:
					f.write('\n'.join(r['diff']) + '\n')
				written += 1
			with open(os.path.join(args.out, 'REPORT.md'), 'w') as f:
				f.write('```\n%s\n```\n' % report)
			print('wrote %s/REPORT.md and %d diffs under %s/'
				% (args.out, written, diffs), file=sys.stderr)
	finally:
		shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
	main()
