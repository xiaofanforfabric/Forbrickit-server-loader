#!/usr/bin/env python3
"""Generate an implementation stub for any paper-api interface the bridge must answer.

The bridge implements a handful of a large interface's methods and refuses the rest with a named exception,
which is the honest answer: a stubbed `getWorld()` returning null turns "this bridge has no worlds yet" into a
NullPointerException inside the plugin, several frames from the cause.

Writing those stubs by hand is pointless work and drifts from the API the moment it moves. This reads the
interface with javap and emits a class whose every abstract method is present with the interface's own
signature, so a plugin links against it exactly as it would against Paper's.

Which methods are answered, and with what, comes from a TABLE file — one per generated class, in this
directory. The table is the part a human writes; everything else is generated. Adding a class means adding a
table, not another script.

Usage:
    python3 tools/gen_stub.py --table ServerImpl.table
    python3 tools/gen_stub.py --table WorldImpl.table --out <path>
    python3 tools/gen_stub.py --list

The classpath javap needs comes from the kernel: `./gradlew -q printBukkitApiClasspath`. It must carry every
type the interface mentions — `Server extends PluginMessageRecipient, ForwardingAudience`, and javap cannot
read an interface's methods without the jars that declare its supertypes.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLES = Path(__file__).resolve().parent / 'stubs'


def javap_type(javap: str, classpath: str, qualified: str) -> list[str]:
    """javap's output lines for one type, with its 80-column wrapping undone.

    javap breaks a long line — a type header with several supertypes, or a method with a long parameter list —
    across physical lines with no marker, so `PluginMessageRecipient` reads as `PluginMe` + `ssageRecipient` on
    the next line. Splitting the header on ` extends ` therefore finds nothing. Rejoining a line that does not
    end a statement with the one after it restores the logical lines javap meant to print.

    A type that cannot be found yields no lines rather than an exception, because callers ask about types they
    have only a name for: a generic's type argument (`T`), an array, a primitive, a nested class reached through
    a field. javap exits non-zero for all of those, and an empty answer is the honest one — the question was
    "what are this type's supertypes", and a type with no class file has none to report. Raising instead stops
    the whole generator, which for a single unanswerable name loses an entire file's worth of work.
    """
    result = subprocess.run([javap, '-cp', classpath, qualified],
                            capture_output=True, text=True, check=False)
    if result.returncode != 0:
        return []
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
        if stripped.startswith('public static ') or stripped.startswith('protected static '):
            continue
        methods.append(stripped)

    # The supertype's methods first would read better, but the interface's own order is the one a reader of the
    # real API file would expect, and it makes a diff against a regenerated file meaningful.
    for supertype in declared_supertypes(javap, classpath, qualified):
        methods.extend(all_abstract_methods(javap, classpath, supertype, seen))

    return covariant_overloads(javap, classpath, methods)


def covariant_overloads(javap: str, classpath: str, methods: list[str]) -> list[str]:
    """Keep one method per (name, parameters), choosing the most specific return type.

    An interface can inherit two declarations that differ ONLY in return type, and Paper does exactly that:
    `PersistentDataViewHolder.getPersistentDataContainer()` returns the read-only `PersistentDataContainerView`,
    while its subinterface `PersistentDataHolder` declares the same method returning the mutable
    `PersistentDataContainer`. A class implementing `World` writes ONE method and the compiler accepts it because
    `PersistentDataContainer` is a subtype of the view — a covariant override.

    Generating both is a compile error ("method is already defined"), and the error names neither the supertype
    nor covariance, so the fix is not obvious from it. Generating only the view would be worse than an error: it
    compiles, and then a plugin calling `getPersistentDataContainer()` on a World gets the read-only type and
    cannot write — a wrong API surface that fails at the plugin's compile time rather than at this bridge's.

    Which one is more specific is a question about the two type names, so it is asked of javap: a return type is
    more specific when javap shows it extending or implementing the other. That is one `javap` call per pair, and
    only for names that actually collide, so the cost is paid where the answer matters.
    """
    by_key: dict[tuple[str, str], list[str]] = {}
    order: list[tuple[str, str]] = []
    for method in methods:
        name, _, parameters, _ = signature_parts(method)
        key = (name, re.sub(r'\s+', '', parameters))
        if key not in by_key:
            by_key[key] = []
            order.append(key)
        by_key[key].append(method)

    chosen = []
    for key in order:
        group = by_key[key]
        if len(group) == 1:
            chosen.append(group[0])
            continue
        winner = group[0]
        for candidate in group[1:]:
            if _returns_subtype(javap, classpath, candidate, winner):
                winner = candidate
        chosen.append(winner)
    return chosen


def _returns_subtype(javap: str, classpath: str, candidate: str, incumbent: str) -> bool:
    """Whether `candidate`'s return type is a subtype of `incumbent`'s, so it may replace it."""
    _, candidate_return, _, _ = signature_parts(candidate)
    _, incumbent_return, _, _ = signature_parts(incumbent)
    if candidate_return == incumbent_return:
        return False
    # Unqualified simple names cannot be compared without the import set the file will have, and the interfaces
    # this runs over spell their own types qualified. Give up rather than guess: keeping the first declaration is
    # the same behaviour as before this function existed, which is a known (if loud) compile error.
    if '.' not in candidate_return or '.' not in incumbent_return:
        return False
    root = candidate_return.split('<')[0]
    target = incumbent_return.split('<')[0]
    # Transitive, not just direct: `PersistentDataContainer extends PersistentDataContainerView` happens to be
    # direct, but nothing promises the next covariant pair is two levels apart, and a direct-only check would
    # answer "no" there — putting the wrong declaration in the generated file, which is the failure this whole
    # function exists to prevent.
    pending, visited = [root], set()
    while pending:
        current = pending.pop()
        if current in visited:
            continue
        visited.add(current)
        for supertype in declared_supertypes(javap, classpath, current):
            if supertype == target:
                return True
            pending.append(supertype)
    return False


def signature_parts(signature: str) -> tuple[str, str, str, str]:
    """(name, return type, parameter list, throws clause) from one javap method line.

    The parameter list is taken by matching parentheses rather than by splitting on the first `(`, because a
    parameter may itself be a generic with a comma inside it.

    javap ends every line with a `;`, and that semicolon belongs to javap's own listing, not to the declaration:
    a method in source has no semicolon before its body. Dropping it here rather than at the end matters, because
    the trailing text is what carries the `throws` clause — left on, the clause reads
    `throws FooException;` and the generated method becomes `... throws FooException; { body }`, which javac
    rejects with "missing method body, or declare abstract" and points at the brace rather than at the semicolon.
    """
    signature = signature.rstrip().removesuffix(';').rstrip()
    open_paren = signature.index('(')
    close_paren = signature.rindex(')')
    before = signature[:open_paren].split()
    name = before[-1]
    return_type = ' '.join(before[1:-1])
    parameters = signature[open_paren + 1:close_paren]
    throws = signature[close_paren + 1:].strip()
    return name, return_type, parameters, throws


def to_source(signature: str, implementation: str, names: list[str] | None = None) -> str:
    """One method: the interface's signature, and either the given body or a named refusal.

    `names` names the parameters for a hand-written body, position by position. Without it they are `arg0`,
    `arg1`, ...; a body that says `worldByNameOrUuid(name)` needs the second form, because a body is written
    against names and the generator cannot invent the ones it used. Passing names here rather than rewriting the
    body keeps the table readable — `getWorld(name)` says what the arity notation could not.
    """
    name, return_type, parameters, throws = signature_parts(signature)
    # javap prints an interface's methods as `public abstract ...`. `abstract` is what makes the method abstract
    # in the INTERFACE, and carrying it into an implementation is a compile error ("abstract method cannot have
    # a body") — but only for the methods that have one, so it reads as a mistake in the body rather than in the
    # modifier. It comes off the return type, and separately off every parameter (the earlier version only did
    # the parameters, which is why every generated method still said `abstract`).
    return_type = re.sub(r'\babstract\b\s*', '', return_type).strip()
    declared_throws = f' {throws}' if throws.startswith('throws') else ''
    parameters = re.sub(r'\babstract\b\s*', '', parameters).strip()
    return_type = source_spelling(return_type)
    parameters = name_parameters(source_spelling(parameters), names)

    if implementation is not None:
        body = '\t\t' + implementation.replace('\n', '\n\t\t')
    else:
        # `throw`, not a bare call: notYet() RETURNS the exception, so a bare call is an expression
        # statement that discards it, and a method with a return type then has no return at all.
        body = f'\t\tthrow notYet("{name}");'
    return f'\t@Override\n\tpublic {return_type} {name}({parameters}){declared_throws} {{\n{body}\n\t}}\n'


def source_spelling(types: str) -> str:
    """Rewrite a type list from how javap prints it to how Java source spells it.

    One difference, and it bites: javap names a nested class with a `$` (`org.bukkit.map.MapCursor$Type`), while
    source spells the same type with a `.` (`org.bukkit.map.MapCursor.Type`). A `$` in source is a compile error
    reading "cannot find symbol" — which points at the type it could not find and not at the punctuation, so the
    message sends a reader looking for a missing class when the class is right there.

    Only `$` between two identifier characters is touched: a `$` is legal in an identifier, so a type genuinely
    named `Foo$bar` (rare, but Java allows it in generated code) keeps its name.
    """
    return re.sub(r'([A-Za-z0-9_$])\$([A-Za-z0-9_])', r'\1.\2', types)


def name_parameters(parameters: str, names: list[str] | None = None) -> str:
    """Give every parameter a distinct name, because javap prints types and Java requires names.

    `arg0`, `arg1`, ... unless `names` supplies one per position — a hand-written body is written against the
    names the table chose, and renaming the parameter out from under it is a compile error inside the body that
    points at a name the body never had a chance to know about. The type's own name is deliberately not the
    default: a body that used the type would read as if it had been written against that type. Distinct numbers
    rather than a bare `arg` everywhere, which collides the moment a method takes two parameters — and a
    collision is a compile error about a variable being "already defined", pointing at the second parameter
    rather than at the missing number.

    Splitting has to respect generics: `Map<String, Integer> a, List<Integer> b` has three commas but two
    parameters, so the depth of `<` is tracked and a comma only separates at depth zero.
    """
    if not parameters:
        return ''

    parts, depth, current = [], 0, ''
    for character in parameters:
        if character == '<':
            depth += 1
        elif character == '>':
            depth -= 1
        if character == ',' and depth == 0:
            parts.append(current)
            current = ''
        else:
            current += character
    parts.append(current)

    named = []
    for index, part in enumerate(parts):
        type_name = part.strip()
        if not type_name:
            continue
        if names is not None and index < len(names) and names[index]:
            named.append(f'{type_name} {names[index]}')
        else:
            named.append(f'{type_name} arg{index}')
    if names is not None and len([n for n in names if n]) > len(named):
        raise SystemExit(f'{len(names)} parameter names given for {len(named)} parameters: {parameters}')
    return ', '.join(named)


def lookup(implemented: dict, name: str, arity: int, parameters: str = '') -> tuple[str, object, object]:
    """The matched table entry key, its body, and its parameter names; (None, None, None) when nothing
    matches.

    Four spellings reach the same method, and a table uses the loosest one that is unambiguous:

    - `name` — the no-argument overload, and nothing else.
    - `name(1)` — the one-argument overload. Fine while the interface declares only one at that arity, which is
      true for most of them.
    - `name(uuid)` — the same overload, with the parameter named so a hand-written body can read it.
    - `name(Identifier)` — an overload picked out by its PARAMETER TYPES. Needed because arity alone cannot tell
      Paper's `sendActionBar(String)` from `sendActionBar(BaseComponent...)`, and picking the first one silently
      leaves the other unimplemented — a compile error saying the class does not override an abstract method,
      naming neither the table nor the collision.

    A spelling is a parameter name when it is not a type, and there is no way to tell them apart from the text.
    The tie is broken by looking at what the interface declares: if the entry names exactly the types of ONE
    overload, it is a type match; otherwise its words are taken as parameter names for the arity's overload.
    """
    exact = f'{name}({arity})'
    if exact in implemented:
        return exact, implemented[exact], None
    if arity == 0 and name in implemented:
        return name, implemented[name], None

    declared_types = [entry.strip() for entry in split_top_level(parameters)]
    for entry, value in implemented.items():
        if not entry.startswith(f'{name}(') or not entry.endswith(')'):
            continue
        inside = entry[len(name) + 1:-1]
        if inside.isdigit() or not inside.strip():
            continue
        parts = [part.strip() for part in split_top_level(inside)]
        if len(parts) != arity:
            continue
        if types_match(parts, declared_types):
            return entry, value, None
    # No entry matched by type, so the words are parameter names for whichever overload has this arity.
    for entry, value in implemented.items():
        if not entry.startswith(f'{name}(') or not entry.endswith(')'):
            continue
        inside = entry[len(name) + 1:-1]
        if inside.isdigit() or not inside.strip():
            continue
        parts = [part.strip() for part in split_top_level(inside)]
        if len(parts) != arity:
            continue
        return entry, value, [part or None for part in parts]
    return None, None, None


def split_top_level(text: str) -> list[str]:
    """Split on commas that are not inside `<...>`, so a generic's arguments stay in one piece."""
    parts, depth, current = [], 0, ''
    for character in text:
        if character == '<':
            depth += 1
        elif character == '>':
            depth -= 1
        if character == ',' and depth == 0:
            parts.append(current)
            current = ''
        else:
            current += character
    parts.append(current)
    return [part for part in (piece.strip() for piece in parts) if part != '' or True]


def types_match(wanted: list[str], declared: list[str]) -> bool:
    """Whether a table's parameter list names the types of one declared overload.

    Compared by SIMPLE NAME, last segment only: the table writes `Identifier` where javap writes
    `net.minecraft.resources.Identifier`, because repeating the package in every table row buries the one thing
    the row is about. A varargs parameter is spelled `T...` on both sides, and a generic's arguments are ignored
    — `List<World>` and `List` both mean java.util.List here, since a table cannot say more than the erasure.
    """
    if len(wanted) != len(declared):
        return False
    for want, have in zip(wanted, declared):
        want_root = want.split('<')[0].strip().rstrip('.').replace('...', '')
        have_root = have.split('<')[0].strip().replace('...', '')
        have_simple = have_root.split('.')[-1]
        # An array in the table is written `Type[]`; strip it on both sides for the name comparison.
        if want_root.endswith('[]'):
            want_root = want_root[:-2]
            have_simple = have_simple.rstrip('[]')
        if want_root != have_simple:
            return False
    return True


def load_table(path: Path) -> dict:
    """Read a table file: a small `key: value` header plus a `[methods]` section.

    Written as a tiny format rather than YAML or TOML because this project carries no parser and the shape is
    three keys and a mapping. A Python file would be shorter still, but then the tables would be code and the
    generator's own comments would compete with theirs for the reader's attention.
    """
    table = {'methods': {}}
    section = None
    for number, raw in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        line = raw.rstrip()
        if not line or line.lstrip().startswith('#'):
            continue
        if line.strip() == '[methods]':
            section = 'methods'
            continue
        if line.startswith('['):
            raise SystemExit(f'{path}:{number}: unknown section {line.strip()}')
        if section == 'methods':
            if '=' not in line:
                raise SystemExit(f'{path}:{number}: expected `name = body`')
            name, _, body = line.partition('=')
            table['methods'][name.strip()] = body.strip()
        else:
            if ':' not in line:
                raise SystemExit(f'{path}:{number}: expected `key: value`')
            key, _, value = line.partition(':')
            table[key.strip()] = value.strip()
    # `skip:` names methods the generated class must NOT emit, because it extends a class that already
    # answers them. Without it a subclass of another generated class re-declares every inherited method as
    # a refusal, which silently overrides the parent's real implementations — the symptom is a subclass
    # where a method that works on the parent throws on the child, with nothing in either file to say why.
    if 'skip' in table:
        table['skip'] = {name.strip() for name in table['skip'].split(',') if name.strip()}
    else:
        table['skip'] = set()
    for required in ('interface', 'class', 'file'):
        if required not in table:
            raise SystemExit(f'{path}: missing `{required}:`')
    return table


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--table', type=Path, help='the table to generate from')
    parser.add_argument('--javap', default='javap')
    parser.add_argument('--api', type=Path,
                        help='a paper-api jar to read instead of the classpath')
    parser.add_argument('--classpath',
                        help='javap classpath; defaults to the --api jar, plus the caller should include '
                             'every jar the interface mentions')
    parser.add_argument('--out', type=Path, help='where to write; defaults to the table\'s `file:`')
    parser.add_argument('--list', action='store_true', help='list the tables beside this script')
    args = parser.parse_args(argv)

    if args.list or not args.table:
        for table in sorted(TABLES.glob('*.table')):
            print(table.name)
        return 0 if args.list else 2

    table = load_table(args.table)
    classpath = args.classpath or (str(args.api) if args.api else None)
    if classpath is None:
        parser.error('one of --classpath or --api is required '
                     '(the kernel prints the first: gradlew -q printBukkitApiClasspath)')

    interface = table['interface']
    signatures = all_abstract_methods(args.javap, classpath, interface)
    if len(signatures) < 5:
        parser.error(f'only {len(signatures)} abstract methods found for {interface} — is this the right jar?')

    implemented = table['methods']
    # A table entry is either `name` (the no-argument form only) or `name(arity)` for a specific overload. Both
    # spellings are checked against what the interface actually declares, so a table naming a method Paper removed
    # fails here rather than silently generating a refusal for it.
    # An entry is `name`, `name(arity)` or — for a body that reads its arguments by name — `name(first,second)`.
    # All three are checked against what the interface actually declares, so a table naming a method Paper removed
    # fails here rather than silently generating a refusal for it.
    declared = set()
    arities = {}
    for signature in signatures:
        owner, _, parameters, _ = signature_parts(signature)
        arity = len([p for p in parameters.split(',') if p.strip()])
        declared.add(owner)
        declared.add(f'{owner}({arity})')
        arities.setdefault(owner, set()).add(arity)

    def canonical(entry: str) -> str:
        """A table entry as the `name(arity)` or bare-`name` form the checks below compare against."""
        if '(' not in entry or not entry.endswith(')'):
            return entry
        owner = entry[:entry.index('(')]
        inside = entry[entry.index('(') + 1:-1]
        if inside.isdigit() or not inside.strip():
            return entry
        return f'{owner}({len([p for p in inside.split(",") if p.strip()])})'

    unknown = {entry for entry in implemented if canonical(entry) not in declared}
    if unknown:
        parser.error(f'the table answers methods {interface} does not declare: {sorted(unknown)}')

    # A bare `name` means the no-argument overload. If the interface has no such overload and the name is
    # overloaded some other way, refusing is the wrong guess: the table's author almost certainly meant one of the
    # other arities, and the alternative — picking the first declared overload — silently answers a question
    # nobody asked. A name with exactly ONE arity is fine, because there is nothing to disambiguate.
    misnamed = []
    for entry in implemented:
        if '(' in entry:
            continue
        if 0 in arities.get(entry, set()):
            continue
        if arities.get(entry):
            misnamed.append(f'{entry} (declare one of: {sorted(arities[entry])})')
    if misnamed:
        parser.error(f'the table uses a bare name for methods with no no-argument overload; '
                     f'write `name(arity)` or `name(param1,param2)`: {misnamed}')

    methods = []
    skipped = []
    # A working copy, because an entry answers ONE method. `sendActionBar(1)` names one overload, but Paper
    # declares three at arity one; leaving the entry in place matches all three, and the two that are not the
    # hand-written one are dropped instead of getting a refusal — a compile error about an unimplemented abstract
    # method that names neither the table nor the collision.
    remaining = dict(implemented)
    for signature in signatures:
        name, _, parameters, _ = signature_parts(signature)
        arity = len([p for p in parameters.split(',') if p.strip()])
        if name in table['skip']:
            # The superclass answers this one; emitting it here would override that answer with a refusal.
            skipped.append(name)
            continue
        entry, body, names = lookup(remaining, name, arity, parameters)
        if entry is None and arity == 0:
            entry, body, names = lookup(remaining, name, 0, parameters)
        if entry is not None:
            del remaining[entry]
        if body == '@template':
            # The body lives in the template, next to the fields it reads. Skipped rather than re-emitted: a
            # generated refusal beside a hand-written method of the same name is a duplicate, and the error it
            # produces names neither this table nor the template.
            skipped.append(name)
            continue
        methods.append(to_source(signature, body, names))
    body = '\n'.join(methods).rstrip()

    template = table.get('template')
    if template is None:
        parser.error(f'{args.table}: missing `template:` (a .java.in beside this script)')
    skeleton = (TABLES / template).read_text(encoding='utf-8')
    # A template with no `{{methods}}` produces a file that has only the hand-written methods and no refusals —
    # and it COMPILES whenever the hand-written set happens to cover the interface, so the omission shows up much
    # later as "ForbricBlock is not abstract and does not override abstract method isSuffocating()", which names
    # neither the template nor the generator. Checking here turns that into one line naming the file.
    if '{{methods}}' not in skeleton:
        parser.error(f'{TABLES / template}: template has no {{{{methods}}}} marker, so the generated methods '
                     f'would be dropped')
    source = skeleton.replace('{{methods}}', body)
    for key in ('class', 'interface', 'package'):
        source = source.replace('{{%s}}' % key, table[key])

    destination = args.out or ROOT / table['file']
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(source, encoding='utf-8')
    # `@template` entries are answered too — just not here. Counting them keeps "answered + refused" equal to
    # the method count, which is the check a reader makes when the numbers look off.
    answered = len(implemented)
    refused = len(signatures) - answered
    try:
        shown = destination.relative_to(ROOT)
    except ValueError:
        shown = destination
    print(f'{shown}: {len(signatures)} methods ({answered} answered, {refused} refused)')
    if skipped:
        print(f'  {len(skipped)} answered in {table.get("template")}: {", ".join(sorted(set(skipped)))}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
