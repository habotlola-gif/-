#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;   // позиция внутри фигуры относительно её центра, px
in ivec2 UV1;  // размер фигуры, px
in ivec2 UV2;  // x — радиус скругления, y — режим (0 — заливка, >0 — размытие тени, -1 — радуга)

out vec4 vertexColor;
out vec2 localPos;
flat out ivec2 shapeSize;
flat out ivec2 shapeParams;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    localPos = UV0;
    shapeSize = UV1;
    shapeParams = UV2;
}
