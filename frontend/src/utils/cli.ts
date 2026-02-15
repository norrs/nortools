export function quoteArg(arg: string): string {
  if (arg.length === 0) return "''";
  if (/[^A-Za-z0-9_@%+=:,./-]/.test(arg)) {
    return `'${arg.replace(/'/g, "'\\''")}'`;
  }
  return arg;
}

export function buildCli(parts: Array<string | null | undefined | false>): string {
  return parts
    .filter((p): p is string => typeof p === 'string' && p.length > 0)
    .map(quoteArg)
    .join(' ');
}
