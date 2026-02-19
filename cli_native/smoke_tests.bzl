load(":smoke/test_names.bzl", "SMOKE_TEST_NAMES")


def _native_linux_cli_smoke_test(name):
    native.sh_test(
        name = name,
        srcs = ["test_native_linux_cli_single_tool_smoke.sh"],
        target_compatible_with = ["@platforms//os:linux"],
        data = [
            "//desktop:native-linux-x64",
            "@jq_linux_amd64//file",
            "testlib.sh",
            "samples/dmarc_report.xml",
            "samples/domains.txt",
            "samples/emails.txt",
            "samples/headers.txt",
        ] + native.glob([
            "smoke/args/*.args",
            "smoke/modes/*.mode",
            "smoke/assertions/*.expected",
            "smoke/assertions/*.jq",
        ]),
        args = [
            "$(location //desktop:native-linux-x64)",
            name,
            "$(location @jq_linux_amd64//file)",
        ],
        timeout = "moderate",
        tags = [
            "manual",
            "local",
            "no-remote",
            "requires-native-image",
        ],
    )


def define_native_linux_cli_smoke_tests():
    smoke_tests = []
    for name in SMOKE_TEST_NAMES:
        _native_linux_cli_smoke_test(name = name)
        smoke_tests.append(":" + name)

    native.test_suite(
        name = "native_linux_cli_all_tools_smoke",
        tests = smoke_tests,
    )

    native.test_suite(
        name = "native_linux_cli_smoke_suite",
        tests = [":native_linux_cli_all_tools_smoke"],
    )
