#!/usr/bin/env python3
"""Generate the {@code Server} implementation stub for the Forbric Bukkit bridge.

Paper's {@code Server} interface has ~200 abstract methods. Writing them by hand is pointless work: the
bridge implements a handful (name, version, logger, plugin manager) and refuses the rest with a named
exception. This script reads the interface with {@code javap} and emits a class that does exactly that, so
the stub is always in step with the API jar it was built against rather than with someone's memory of it.

The bridge lives in the kernel's game side ({@code src/runtime/java/org/cn/xiaofan/bukkit/}), so that is
where the output goes. It is a separate Gradle project no longer -- the bridge compiles as part of
forbric-kernel-runtime.jar.

Usage:  python3 tools/gen_server_stub.py [--javap PATH] [--api JAR] [--out FILE]

The API jar defaults to the one the kernel fetched into the game directory; pass --api to point at another
(any published paper-api jar works -- the script only reads it with javap). It is NOT on the compile
classpath here because the kernel never bundles it, only compiles against it.

The generated file carries a header saying it is generated; edits belong in the template below, not in the
output. Re-run it after bumping the paper-api version in PaperApiFetcher and in build.gradle together.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# PaperApiFetcher's cache, relative to the game directory; the dev game directory is .dev/server. Kept as the
# first guess only -- --api exists because a developer may run this before ever starting a server.
DEFAULT_API = ROOT / '.dev' / 'server' / '.forbric-kernel' / 'bukkit-libs' / 'io' / 'papermc' / 'paper' \
    / 'paper-api' / '26.2.build.133-stable' / 'paper-api-26.2.build.133-stable.jar'
DEFAULT_OUT = ROOT / 'src' / 'runtime' / 'java' / 'org' / 'cn' / 'xiaofan' / 'bukkit' / 'ServerImpl.java'

# Methods the bridge answers itself. Everything else refuses with UnsupportedOperationException, which is the
# honest answer: a wrong value from a stubbed method is far harder to trace than a named refusal.
IMPLEMENTED = {
    'getName': 'return BRAND;',
    'getVersion': 'return version;',
    'getBukkitVersion': 'return BUKKIT_VERSION;',
    'getMinecraftVersion': 'return MINECRAFT_VERSION;',
    'getLogger': 'return logger;',
    'getPluginManager': 'return pluginManager;',
    'getPluginsFolder': 'return pluginsFolder.toFile();',
    'isPrimaryThread': 'return true;',
    'getUpdateFolder': 'return "update";',
}

HEADER = '''/*
 * Forbric Bukkit bridge — the {{@code Server}} the plugins see.  *** GENERATED FILE — DO NOT EDIT ***
 *
 * Produced by tools/gen_server_stub.py against {api}. Re-run that script after bumping the api; editing
 * this file by hand means the next run silently discards the edit.
 */
package org.cn.xiaofan.bukkit;

{imports}

/**
 * The {{@code Server}} a plugin receives from {{@code getServer()}}.
 *
 * <p>{{@code org.bukkit.Server}} has ~200 abstract methods. This bridge implements the few a plugin needs to
 * start — its identity, its logger, and the plugin manager — and refuses the rest with a named
 * {{@link UnsupportedOperationException}}. Refusing loudly is deliberate: a stubbed {{@code getWorld()}}
 * returning {{@code null}} would turn "this bridge has no worlds yet" into a {{@code NullPointerException}}
 * inside the plugin, several frames away from the cause.
 *
 * <p>Every method below carries the interface's own signature, so a plugin links against this class exactly
 * as it would against Paper's.
 */
final class ServerImpl implements org.bukkit.Server {{

	static final String BRAND = "Forbric";
	static final String BUKKIT_VERSION = "26.2-R0.1-SNAPSHOT";
	static final String MINECRAFT_VERSION = "26.2";

	private final java.util.logging.Logger logger;
	private final org.bukkit.plugin.PluginManager pluginManager;
	private final java.nio.file.Path pluginsFolder;
	private final String version;

	ServerImpl(java.util.logging.Logger logger, org.bukkit.plugin.PluginManager pluginManager,
			java.nio.file.Path pluginsFolder, String version) {{
		this.logger = logger;
		this.pluginManager = pluginManager;
		this.pluginsFolder = pluginsFolder;
		this.version = version;
	}}

	private static UnsupportedOperationException notYet(String what) {{
		return new UnsupportedOperationException(
				"[Forbric/Bukkit] Server." + what + "() is not implemented by this bridge yet");
	}}

{methods}
}}
'''


def javap_type(javap: str, classpath: str, qualified: str) -> list[str]:
    """javap's output lines for one type, with its 80-column wrapping undone.

    javap breaks a long line — a type header with several supertypes, or a method with a long parameter list —
    across physical lines with no marker, so `PluginMessageRecipient` reads as `PluginMe` + `ssageRecipient` on
    the next line. Splitting the header on ` extends ` therefore finds nothing. Rejoining a line that does not
    end a statement with the one after it restores the logical lines javap meant to print.

    The classpath must carry every type the interface mentions: `Server extends … ForwardingAudience`, and javap
    cannot read that interface's methods unless the adventure jar is on the path too.
    """
    result = subprocess.run([javap, '-cp', classpath, qualified],
                            capture_output=True, text=True, check=True)
    return join_wrapped_lines(result.stdout.splitlines())


def join_wrapped_lines(lines: list[str]) -> list[str]:
    """Undo javap's column wrapping.

    A logical line ends with `;`, `{` or `}`; everything else continues onto the next physical line.
    """
    joined = []
    pending = ''
    for line in lines:
        if pending:
            line = pending + line.lstrip()
            pending = ''
        stripped = line.rstrip()
        if not stripped:
            continue
        if stripped.endswith((';', '{', '}')):
            joined.append(stripped)
        else:
            pending = stripped
    if pending:
        joined.append(pending)
    return joined


def declared_supertypes(javap: str, classpath: str, qualified: str) -> list[str]:
    """The interfaces a type extends directly.

    javap lists a type's own methods but not the ones it inherits, and `Server extends PluginMessageRecipient,
    ForwardingAudience` — so the last two methods `Server` demands are not in its own listing at all. Walking the
    hierarchy is what makes the stub complete.
    """
    lines = javap_type(javap, classpath, qualified)
    if not lines:
        return []

    header = lines[0]
    if ' extends ' not in header:
        return []
    extended = header.split(' extends ', 1)[1].rstrip(' {')
    return [name.strip() for name in extended.split(',') if name.strip()]


def all_abstract_methods(javap: str, classpath: str, qualified: str, seen=None) -> list[str]:
    """Every abstract method a type requires, its own and its supertypes', de-duplicated by name+parameters."""
    seen = seen if seen is not None else set()
    if qualified in seen:
        return []
    seen.add(qualified)

    methods = []
    for line in javap_type(javap, classpath, qualified):
        stripped = line.strip()
        # A method line has a parameter list; a field, the type header and `Compiled from` do not. `static {};`
        # is a static initialiser, not a method to override, and it has no parameter list either.
        if '(' not in stripped or stripped.startswith('Compiled from'):
            continue
        if not stripped.startswith('public ') and not stripped.startswith('protected '):
            continue
        # A `default` method already has a body, so it must not be reimplemented — `public default` is also not
        # valid Java syntax, which is what javap prints for it.
        if ' default ' in stripped:
            continue
        # A `static` interface method is not inherited and cannot be overridden; implementing it is a compile
        # error (`public public static …`). ForwardingAudience's two factories are exactly this.
        if ' static ' in stripped:
            continue
        methods.append(stripped)

    for supertype in declared_supertypes(javap, classpath, qualified):
        methods.extend(all_abstract_methods(javap, classpath, supertype, seen))
    return methods


def deduplicate(signatures: list[str]) -> list[str]:
    """One entry per method, keyed by name and parameter list.

    A subtype may redeclare a supertype's method to sharpen its return type; both lines appear in the walk, and
    emitting both would be a duplicate method. The first occurrence wins, which is the subtype's — the narrower
    signature, and the one a caller sees.
    """
    kept = []
    seen = set()
    for signature in signatures:
        name = method_name(signature)
        parameters = re.search(r'\((.*)\)', signature)
        key = (name, parameters.group(1) if parameters else '')
        if key in seen:
            continue
        seen.add(key)
        kept.append(signature)
    return kept


def javap_methods(javap: str, api: Path) -> list[str]:
    """The interface's abstract methods, as javap prints them."""
    result = subprocess.run([javap, '-cp', str(api), 'org.bukkit.Server'],
                            capture_output=True, text=True, check=True)
    return [line.strip() for line in result.stdout.splitlines() if ' abstract ' in line]


def method_name(signature: str) -> str:
    match = re.search(r'([A-Za-z_$][A-Za-z0-9_$]*)\s*\(', signature)
    if not match:
        raise ValueError(f'cannot find a method name in: {signature}')
    return match.group(1)


def name_parameters(parameter_list: str) -> str:
    """Give every parameter a name.

    javap prints parameter types only — `setMaxPlayers(int)`, not `setMaxPlayers(int arg0)` — and Java requires
    the name. Splitting on top-level commas (a comma inside `Map<String, Integer>` is not a separator), each part
    becomes `type argN`. The names never reach a caller, so positional ones are honest.
    """
    parameter_list = parameter_list.strip()
    if not parameter_list:
        return ''

    parts = []
    depth = 0
    current = ''
    for char in parameter_list:
        if char in '<([':
            depth += 1
        elif char in '>)]':
            depth -= 1
        if char == ',' and depth == 0:
            parts.append(current.strip())
            current = ''
        else:
            current += char
    if current.strip():
        parts.append(current.strip())

    return ', '.join(f'{part} arg{index}' for index, part in enumerate(parts))


def internal_to_source_types(source: str) -> str:
    """Rewrite javap's nested-type syntax into Java's.

    javap writes a nested class as `org.bukkit.BanList$Type`; Java source needs `org.bukkit.BanList.Type`.
    Only `$` between an identifier and another identifier is rewritten — a `$` inside a single class name (which
    javac allows and Kotlin's compilers produce) would otherwise be broken. In a method signature javap emits,
    the `$` is always the nested-class separator.
    """
    return re.sub(r'([A-Za-z0-9_])\$([A-Za-z0-9_])', r'\1.\2', source)


def to_source(signature: str) -> str:
    """Turn `public abstract java.lang.String getName();` into a Java override."""
    body = re.sub(r'^public abstract\s+', '', signature).rstrip(';').strip()
    name = method_name(signature)
    # java.lang is the only package safe to shorten unconditionally: java.lang.String is resolvable everywhere,
    # while stripping org.bukkit. turns `org.bukkit.plugin.PluginManager` into the invalid `plugin.PluginManager`.
    # Everything else stays fully qualified, which is verbose but never wrong.
    body = internal_to_source_types(body.replace('java.lang.', ''))

    declared_throws = ''
    if ' throws ' in body:
        body, thrown = body.split(' throws ', 1)
        declared_throws = ' throws ' + thrown

    # Rebuild the parameter list with names; `body` still carries the unnamed one.
    open_paren = body.index('(')
    close_paren = body.rindex(')')
    head = body[:open_paren]
    raw_parameters = body[open_paren + 1:close_paren]
    parameters = name_parameters(raw_parameters)

    # Only a no-argument method may be answered from IMPLEMENTED. `getWorld` has three overloads; matching by
    # name alone would give all three the body written for one of them.
    implementation = IMPLEMENTED.get(name) if not raw_parameters.strip() else None
    if implementation is not None:
        inner = '\t\t' + implementation
    else:
        inner = f'\t\tthrow notYet("{name}");'

    return f'\t@Override\n\tpublic {head}({parameters}){declared_throws} {{\n{inner}\n\t}}\n'


def collect_imports(signatures: list[str]) -> str:
    """No imports.

    Every method body keeps its parameter and return types fully qualified, so an import is never needed — and
    never dangerous. An earlier version shortened `org.bukkit.plugin.PluginManager` to `plugin.PluginManager`,
    which is not a type at all; leaving the names whole removes that whole class of mistake.
    """
    return ''


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--javap', default='javap')
    parser.add_argument('--api', type=Path, default=DEFAULT_API)
    parser.add_argument('--classpath', help='the full compile classpath (see gradle printCompileClasspath); '
                                            'defaults to --api alone, which cannot resolve inherited methods')
    parser.add_argument('--out', type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)

    if not args.api.is_file():
        parser.error(f'api jar not found: {args.api}')
    classpath = args.classpath or str(args.api)

    signatures = deduplicate(all_abstract_methods(args.javap, classpath, 'org.bukkit.Server'))
    if len(signatures) < 100:
        parser.error(f'only {len(signatures)} abstract methods found — is this the right jar?')

    methods = '\n'.join(to_source(signature) for signature in signatures)
    source = HEADER.format(api=args.api.name, imports=collect_imports(signatures), methods=methods.rstrip())
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(source, encoding='utf-8')
    print(f'{args.out}: {len(signatures)} methods '
          f'({len(IMPLEMENTED)} implemented, {len(signatures) - len(IMPLEMENTED)} refused)')


if __name__ == '__main__':
    sys.exit(main())
