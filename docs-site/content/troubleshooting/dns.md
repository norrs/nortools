# DNS Troubleshooting

## No Records Found

The name may exist but not publish that record type.

```bash
nortools a example.com
nortools mx example.com
nortools txt example.com
```

## Different Resolver Answers

Use `--server` to compare resolvers.

```bash
nortools mx --server 1.1.1.1 example.com
nortools mx --server 8.8.8.8 example.com
```
