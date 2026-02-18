def _git_build_info_impl(ctx):
    out = ctx.outputs.out
    info = ctx.info_file
    version = ctx.version_file

    ctx.actions.run_shell(
        inputs = [info, version],
        outputs = [out],
        arguments = [info.path, version.path, out.path],
        command = """
set -euo pipefail

file_a="$1"
file_b="$2"
out="$3"

get_value() {
  key="$1"
  file="$2"
  awk -v k="$key" '$1 == k { $1=""; sub(/^ /, ""); print; exit }' "$file"
}

get_value_any() {
  key="$1"
  value="$(get_value "$key" "$file_a")"
  if [ -z "${value}" ]; then
    value="$(get_value "$key" "$file_b")"
  fi
  echo "$value"
}

git_commit="$(get_value_any STABLE_GIT_COMMIT)"
git_branch="$(get_value_any STABLE_GIT_BRANCH)"
git_dirty="$(get_value_any STABLE_GIT_DIRTY)"
git_describe="$(get_value_any STABLE_GIT_DESCRIBE)"
scm_revision="$(get_value_any BUILD_SCM_REVISION)"
scm_branch="$(get_value_any BUILD_SCM_BRANCH)"
scm_status="$(get_value_any BUILD_SCM_STATUS)"

git_commit="${git_commit:-unknown}"
git_branch="${git_branch:-unknown}"
git_dirty="${git_dirty:-unknown}"
git_describe="${git_describe:-unknown}"
scm_revision="${scm_revision:-unknown}"
scm_branch="${scm_branch:-unknown}"
scm_status="${scm_status:-unknown}"

cat > "$out" <<EOF
git.commit=$git_commit
git.branch=$git_branch
git.dirty=$git_dirty
git.describe=$git_describe
build.version=$git_describe
build.changelist=$scm_revision
build.scm.branch=$scm_branch
build.scm.status=$scm_status
EOF
""",
    )

    return [DefaultInfo(files = depset([out]))]

git_build_info = rule(
    implementation = _git_build_info_impl,
    outputs = {"out": "git-build-info.properties"},
)

def _build_data_info_impl(ctx):
    out = ctx.outputs.out
    info = ctx.info_file
    version = ctx.version_file

    ctx.actions.run_shell(
        inputs = [info, version],
        outputs = [out],
        arguments = [
            info.path,
            version.path,
            out.path,
            ctx.attr.build_target,
            ctx.attr.main_class,
        ],
        command = """
set -euo pipefail

file_a="$1"
file_b="$2"
out="$3"
build_target="$4"
main_class="$5"

get_value() {
  key="$1"
  file="$2"
  awk -v k="$key" '$1 == k { $1=""; sub(/^ /, ""); print; exit }' "$file"
}

get_value_any() {
  key="$1"
  value="$(get_value "$key" "$file_a")"
  if [ -z "${value}" ]; then
    value="$(get_value "$key" "$file_b")"
  fi
  echo "$value"
}

build_timestamp="$(get_value_any BUILD_TIMESTAMP)"
git_describe="$(get_value_any STABLE_GIT_DESCRIBE)"
if [ -z "${build_timestamp}" ]; then
  build_timestamp="unknown"
fi
if [ -z "${git_describe}" ]; then
  git_describe="unknown"
fi

cat > "$out" <<EOF
build.target=$build_target
main.class=$main_class
build.timestamp.as.int=$build_timestamp
build.version=$git_describe
EOF
""",
    )

    return [DefaultInfo(files = depset([out]))]

build_data_info = rule(
    implementation = _build_data_info_impl,
    attrs = {
        "build_target": attr.string(mandatory = True),
        "main_class": attr.string(mandatory = True),
    },
    outputs = {"out": "build-data.properties"},
)
