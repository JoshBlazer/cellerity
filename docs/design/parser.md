# Design note: `cellerity-formula` parser (M1)

Written before the implementation. The failing tests were committed with the stub signatures.

## Scope

Input is formula text as stored in `.xlsx` (A1 style, English function names, no leading `=`, though one
is tolerated). Output is an immutable AST with character spans.

In scope: numbers, strings, booleans, errors, A1 and sheet-qualified references, 3D and external prefixes,
whole rows and columns, defined names, functions including empty arguments, all operators with Excel
precedence, percent, concatenation, and array constants.

Parsed but not evaluated in v1: structured references (kept as raw text), external and 3D references.
Dynamic-array syntax (`A1#`) never appears in stored text, which uses `_xlfn.ANCHORARRAY(...)`.

## Grammar, loosest binding first

```
formula     := "="? comparison EOF
comparison  := concat (("=" | "<>" | "<" | "<=" | ">" | ">=") concat)*
concat      := additive ("&" additive)*
additive    := term (("+" | "-") term)*
term        := power (("*" | "/") power)*
power       := postfix ("^" postfix)*                    left associative: 2^3^2 is 64
postfix     := unary "%"*
unary       := ("+" | "-") unary | union
union       := intersect ("," intersect)*                only directly inside parentheses
intersect   := range (WHITESPACE range)*                 only between reference-like operands
range       := primary (":" primary)*
primary     := literal | reference | name | call | "(" union-enabled comparison ")" | array
```

Quirks this encodes deliberately:

- **Unary minus binds tighter than `^`.** `-2^2` is `(-2)^2`, which is `4`.
- **`^` is left associative.** `2^3^2` is `(2^3)^2`.
- **`,` is ambiguous.** It separates arguments in a call and means union only inside parentheses, so
  `SUM(A1,B1)` has two arguments and `SUM((A1,B1))` has one.
- **Whitespace is an operator.** `A1:B2 B1:C3` is an intersection, and `A1 + B1` is not.
- **Cell-like names.** `LOG10(1)` is a call, `XFE1` is a name because it is past the last column, and `A1B`
  is a name.

## AST

`Expr` is a sealed interface of records, and each has a `Span`. `Exprs.withoutSpans` strips spans for
structural comparison. Parentheses are kept as `Paren` nodes, and numbers keep their source text, so
printing does not change meaning or form.

## Error recovery

`FormulaParser.parse` never throws on any input. It returns the tree it could build, with `Invalid` nodes
at failures and a `Diagnostic` per problem, each with a precise `Span`. After an error, the parser skips to
the next `,`, `)`, or end of input and carries on, so one formula can report several problems. Nesting
deeper than 512 levels is a diagnostic rather than a stack overflow.

## Shared formulas

Followers in `.xlsx` store only an `si` index. `ReferenceShifter` moves relative endpoints by the offset
from the master, wrapping at sheet limits as POI does, and leaves absolute endpoints alone. `ParsedWorkbook`
applies this and links array-range members to their masters.

`ParsedWorkbook` makes `cellerity-formula` depend on `cellerity-io`. The parser itself uses neither.

## Tests

- Examples from Microsoft's documented operator precedence.
- jqwik: `parse(print(ast))` equals `ast`, for generated ASTs.
- jqwik: arbitrary strings never throw, and all spans stay in bounds.
- Corpus: `make eval` reports the parse rate and re-parses every printed formula.

## Honest limits

Only English function names and A1 style, which is what `.xlsx` stores. The parser accepts some text Excel
would refuse, such as an unknown function name. That is the evaluator's concern, and it is reported as an
unsupported function.
