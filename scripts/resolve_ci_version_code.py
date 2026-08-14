#!/usr/bin/env python3
import argparse


def positive_int(raw: str) -> int:
    try:
        value = int(raw)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a positive integer") from error
    if value <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-version-code", type=positive_int, required=True)
    parser.add_argument("--run-number", type=positive_int, required=True)
    parser.add_argument("--channel", choices=("test", "prod"), required=True)
    parser.add_argument("--override", type=positive_int)
    parser.add_argument("--test-base", type=positive_int)
    parser.add_argument("--prod-base", type=positive_int)
    args = parser.parse_args()

    if args.override is not None:
        print(args.override)
        return

    channel_base = args.base_version_code
    if args.channel == "test" and args.test_base is not None:
        channel_base = args.test_base
    if args.channel == "prod" and args.prod_base is not None:
        channel_base = args.prod_base
    print(channel_base + args.run_number)


if __name__ == "__main__":
    main()
