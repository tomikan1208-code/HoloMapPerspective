"""Prepare a textured, static PMX model for HoloMap's GPU renderer; leaves the source intact."""
import argparse
import hashlib
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image


class Reader:
    def __init__(self, path):
        self.file = path.open("rb")
        if self.file.read(4) != b"PMX ":
            raise ValueError("Not a PMX model")
        self.version = self.unpack("f")[0]
        self.flags = list(self.file.read(self.unpack("B")[0]))
        self.encoding = "utf-16-le" if self.flags[0] == 0 else "utf-8"

    def unpack(self, shape):
        return struct.unpack("<"+shape, self.file.read(struct.calcsize("<"+shape)))

    def text(self):
        size = self.unpack("i")[0]
        if not 0 <= size <= 16_000_000:
            raise ValueError("Invalid PMX text length")
        return self.file.read(size).decode(self.encoding)

    def index(self, size):
        return self.unpack({1: "b", 2: "h", 4: "i"}[size])[0]


def prepare(source, output):
    reader = Reader(source)
    model_name, english_name, comment, english_comment = [reader.text() for _ in range(4)]
    vertex_count = reader.unpack("i")[0]
    vertices = np.empty((vertex_count, 3), dtype="<f4")
    uv = np.empty((vertex_count, 2), dtype="<f4")
    for index in range(vertex_count):
        vertices[index] = reader.unpack("3f")
        reader.unpack("3f")  # normals; flat world-space normals are calculated by the GPU
        uv[index] = reader.unpack("2f")
        reader.file.read(reader.flags[1]*16)
        deformation = reader.unpack("B")[0]
        bone_size = reader.flags[5]
        sizes = {0: bone_size, 1: bone_size*2+4, 2: bone_size*4+16,
                 3: bone_size*2+40, 4: bone_size*4+16}
        reader.file.read(sizes[deformation]+4)
    index_count = reader.unpack("i")[0]
    indices = np.frombuffer(reader.file.read(index_count*reader.flags[2]),
                            dtype={1: "u1", 2: "<u2", 4: "<u4"}[reader.flags[2]])
    textures = [reader.text() for _ in range(reader.unpack("i")[0])]
    materials = []
    for _ in range(reader.unpack("i")[0]):
        name, english = reader.text(), reader.text()
        diffuse = reader.unpack("4f")
        reader.file.read(12+4+12+1+16+4)
        texture = reader.index(reader.flags[3])
        reader.index(reader.flags[3])
        reader.file.read(1)
        shared = reader.unpack("B")[0]
        reader.file.read(1 if shared else reader.flags[3])
        reader.text()
        faces = reader.unpack("i")[0]
        materials.append(dict(name=name, diffuse=diffuse, texture=texture, vertices=faces))
    if sum(material["vertices"] for material in materials) != index_count:
        raise ValueError("PMX material ranges do not cover the mesh")
    output.mkdir(parents=True, exist_ok=True)
    texture_directory = output/"textures"
    texture_directory.mkdir(exist_ok=True)
    copied = {}
    for material in materials:
        number = material["texture"]
        if number < 0 or number in copied:
            continue
        name = textures[number].replace("\\", "/")
        image_path = (source.parent/name).resolve()
        if not image_path.is_file():
            raise FileNotFoundError(f"Missing texture: {image_path}")
        destination = texture_directory/f"texture-{number:02d}.png"
        with Image.open(image_path) as image:
            image.convert("RGBA").save(destination)
        copied[number] = destination.relative_to(output).as_posix()
    # Match Blender's MMD export convention: metres, Y-up, Z toward the viewer.
    vertices *= .08
    vertices[:, 2] *= -1
    uv[:, 1] = 1-uv[:, 1]
    bounds = [*vertices.min(axis=0), *vertices.max(axis=0)]
    dtype = np.dtype([("position", "<f4", (3,)), ("rgba", "<u4"), ("uv", "<f4", (2,))])
    manifest = {"version": 1, "segments": []}
    cursor = 0
    with (output/"model.hmesh").open("wb") as binary:
        binary.write(b"HMAPTEX1"+struct.pack("<i6f", index_count//3, *bounds))
        for material in materials:
            count = material["vertices"]
            selected = indices[cursor:cursor+count].reshape(-1, 3)[:, [0, 2, 1]].reshape(-1)
            rgba = np.clip(np.array(material["diffuse"])*255, 0, 255).round().astype(int)
            packed = int(rgba[3]<<24 | rgba[0]<<16 | rgba[1]<<8 | rgba[2])
            values = np.empty(count, dtype=dtype)
            values["position"], values["uv"], values["rgba"] = vertices[selected], uv[selected], packed
            binary.write(values.tobytes())
            manifest["segments"].append(dict(first=cursor, count=count, material=material["name"], texture=copied.get(material["texture"])))
            cursor += count
    (output/"model.hmesh.materials.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    report = dict(source=str(source), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                  name=model_name, triangles=index_count//3, vertices=vertex_count,
                  materials=len(materials), textures=len(copied), bounds=[float(value) for value in bounds],
                  output=str(output/"model.hmesh"))
    (output/"preparation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    prepare(arguments.source, arguments.output)
