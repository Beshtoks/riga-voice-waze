import csv
import json
import re
import sys
from pathlib import Path


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip())


def russian_aliases_for_street(official: str) -> list[str]:
    s = official.lower().strip()

    replacements = [
        ("ā", "а"),
        ("č", "ч"),
        ("ē", "е"),
        ("ģ", "г"),
        ("ī", "и"),
        ("ķ", "к"),
        ("ļ", "л"),
        ("ņ", "н"),
        ("š", "ш"),
        ("ū", "у"),
        ("ž", "ж"),
        ("a", "а"),
        ("b", "б"),
        ("c", "ц"),
        ("d", "д"),
        ("e", "е"),
        ("f", "ф"),
        ("g", "г"),
        ("h", "х"),
        ("i", "и"),
        ("j", "й"),
        ("k", "к"),
        ("l", "л"),
        ("m", "м"),
        ("n", "н"),
        ("o", "о"),
        ("p", "п"),
        ("r", "р"),
        ("s", "с"),
        ("t", "т"),
        ("u", "у"),
        ("v", "в"),
        ("z", "з"),
    ]

    result = s
    for src, dst in replacements:
        result = result.replace(src, dst)

    result = normalize_text(result)

    aliases = {result}
    aliases.add(result.replace("й", "и"))

    return sorted(a for a in aliases if a)


def compute_priority(city: str) -> int:
    city_l = city.lower().strip()

    if city_l in {"rīga", "riga"}:
        return 100
    if city_l in {"jūrmala", "jurmala", "mārupe", "marupe"}:
        return 90
    if city_l in {
        "daugavpils",
        "liepāja",
        "liepaja",
        "jelgava",
        "ventspils",
        "rēzekne",
        "rezekne",
        "jēkabpils",
        "jekabpils",
        "valmiera",
        "ogre",
    }:
        return 80

    return 50


def extract_city_from_std(std_value: str) -> str:
    """
    Пример STD:
    '1. Āmu iela, Rīga'
    '1. Krūmu iela, Jēči, Dunikas pag., Dienvidkurzemes nov.'
    '1. Kumeļu iela, Daugavpils'
    Берём первый элемент после названия улицы.
    """
    std_value = normalize_text(std_value.strip('"'))
    if not std_value:
        return ""

    parts = [p.strip() for p in std_value.split(",") if p.strip()]
    if len(parts) < 2:
        return ""

    city = parts[1]

    city = re.sub(r"^\d+\.\s*", "", city).strip()

    return city


def build_json_from_csv(csv_path: Path, out_path: Path) -> tuple[int, int]:
    merged: dict[tuple[str, str], dict] = {}
    total_rows = 0

    with csv_path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f, delimiter=",")

        if not reader.fieldnames:
            raise ValueError("CSV без заголовков.")

        needed = {"NOSAUKUMS", "STD"}
        missing = needed - set(reader.fieldnames)
        if missing:
            raise ValueError(f"В CSV нет нужных колонок: {sorted(missing)}")

        for row in reader:
            total_rows += 1

            official = normalize_text(row.get("NOSAUKUMS", "").strip('"'))
            std_value = row.get("STD", "")

            if not official:
                continue

            city = extract_city_from_std(std_value)
            priority = compute_priority(city)

            key = (official, city)

            if key not in merged:
                merged[key] = {
                    "official": official,
                    "city": city,
                    "aliases": set(russian_aliases_for_street(official)),
                    "priority": priority,
                }
            else:
                merged[key]["aliases"].update(russian_aliases_for_street(official))

    result = []

    for item in merged.values():
        result.append({
            "official": item["official"],
            "city": item["city"],
            "aliases": sorted(item["aliases"]),
            "priority": item["priority"],
        })

    result.sort(key=lambda x: (
        -int(x["priority"]),
        str(x["city"]).lower(),
        str(x["official"]).lower(),
    ))

    out_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )

    return total_rows, len(result)


def main() -> int:
    if len(sys.argv) < 2:
        print("Использование:")
        print("  py build_streets_latvia.py AW_IELA.CSV streets_latvia.json")
        return 1

    csv_path = Path(sys.argv[1]).expanduser().resolve()
    out_path = (
        Path(sys.argv[2]).expanduser().resolve()
        if len(sys.argv) >= 3
        else Path("streets_latvia.json").resolve()
    )

    if not csv_path.exists():
        print(f"Файл не найден: {csv_path}")
        return 2

    try:
        total_rows, unique_streets = build_json_from_csv(csv_path, out_path)
    except Exception as e:
        print(f"Ошибка: {e}")
        return 3

    print("Готово.")
    print(f"Прочитано строк CSV: {total_rows}")
    print(f"Уникальных улиц сохранено: {unique_streets}")
    print(f"Файл JSON: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())