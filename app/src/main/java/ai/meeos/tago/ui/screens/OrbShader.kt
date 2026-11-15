package ai.meeos.tago.ui.screens

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ShaderBrush
import kotlinx.coroutines.isActive
import kotlin.math.exp

/*
 * 말로 검색 듣는 구슬의 GPU 판(11-15, 사용자 "원형에 기하학적인 모양" · "3d 그래픽적으로" · "너무 저퀄임 gpt한테도 부탁해보자" · "기하학적인 모양이랬지" · "최선인가").
 * 짙은 초록 유리 구슬(해석 구 음영 · 프레넬 테 · 넓은/또렷한 반사광 · 바깥 빛) 안에 정이십면체 크리스털을 레이마칭으로 그린다(GDF — 면 법선 10개의 최대값,
 * 32걸음 + 안쪽 빛 8샘플, 구슬 원 안·도형 경계 구 안에서만). 면은 빛 방향 음영 · 프레넬 · 흰 반사광 · 주황 반사, 모서리는 가장 큰 두 평면 거리 차로 찾아
 * 민트로 빛나고(말하면 주황), 안쪽 주황 핵 빛이 면 너머로 비친다. 말소리 크기(0~1)만큼 핵이 커지고 밝아지며 모서리 빛이 넓어지고 바깥 빛이 따뜻해진다.
 * 셰이더 원안: GPT(Codex CLI) 답 — 조건(원 안의 기하 도형 · 그림 없음 · 앱 색)을 걸어 받음. API 33 아래·컴파일 실패면 [fallback](캔버스 정이십면체).
 */
private const val ORB_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float iLevel;

const float3 GREEN = float3(0.00392157, 0.21568627, 0.16470588);
const float3 MINT  = float3(0.56078431, 0.82745098, 0.65882353);
const float3 AMBER = float3(0.96078431, 0.65098039, 0.13725490);
const float3 WHITE = float3(1.0);

// Ten unit normals, each representing two opposite faces of a regular icosahedron (20 faces).
const float3 N0 = float3( 0.577350269,  0.577350269,  0.577350269);
const float3 N1 = float3(-0.577350269,  0.577350269,  0.577350269);
const float3 N2 = float3( 0.577350269, -0.577350269,  0.577350269);
const float3 N3 = float3( 0.577350269,  0.577350269, -0.577350269);
const float3 N4 = float3( 0.0,          0.356822090,  0.934172359);
const float3 N5 = float3( 0.0,         -0.356822090,  0.934172359);
const float3 N6 = float3( 0.356822090,  0.934172359,  0.0);
const float3 N7 = float3(-0.356822090,  0.934172359,  0.0);
const float3 N8 = float3( 0.934172359,  0.0,          0.356822090);
const float3 N9 = float3( 0.934172359,  0.0,         -0.356822090);

// Conservative signed distance bound; h is the inradius.
float icoDistance(float3 p, float h) {
    float d = abs(dot(p, N0));
    d = max(d, abs(dot(p, N1)));
    d = max(d, abs(dot(p, N2)));
    d = max(d, abs(dot(p, N3)));
    d = max(d, abs(dot(p, N4)));
    d = max(d, abs(dot(p, N5)));
    d = max(d, abs(dot(p, N6)));
    d = max(d, abs(dot(p, N7)));
    d = max(d, abs(dot(p, N8)));
    d = max(d, abs(dot(p, N9)));
    return d - h;
}

// Track the two largest plane supports and the active SDF gradient.
void rankPlane(float3 p, float3 planeNormal, inout float2 largest, inout float3 gradient) {
    float projection = dot(p, planeNormal);
    float support = abs(projection);
    if (support > largest.x) {
        largest.y = largest.x;
        largest.x = support;
        gradient = planeNormal * (projection < 0.0 ? -1.0 : 1.0);
    } else {
        largest.y = max(largest.y, support);
    }
}

float3 icoGradient(float3 p, out float edgeGap) {
    float2 largest = float2(-1.0);
    float3 gradient = N0;
    rankPlane(p, N0, largest, gradient);
    rankPlane(p, N1, largest, gradient);
    rankPlane(p, N2, largest, gradient);
    rankPlane(p, N3, largest, gradient);
    rankPlane(p, N4, largest, gradient);
    rankPlane(p, N5, largest, gradient);
    rankPlane(p, N6, largest, gradient);
    rankPlane(p, N7, largest, gradient);
    rankPlane(p, N8, largest, gradient);
    rankPlane(p, N9, largest, gradient);
    edgeGap = max(largest.x - largest.y, 0.0);
    return gradient;
}

// Inverse yaw followed by inverse pitch. rotation = (cos(yaw), sin(yaw), cos(pitch), sin(pitch)).
float3 toLocal(float3 p, float4 rotation) {
    float3 q = float3(rotation.x * p.x - rotation.y * p.z, p.y, rotation.y * p.x + rotation.x * p.z);
    return float3(q.x, rotation.z * q.y + rotation.w * q.z, -rotation.w * q.y + rotation.z * q.z);
}

half4 main(float2 fragCoord) {
    float shortSide = max(min(iResolution.x, iResolution.y), 1.0);
    float pixel = 2.0 / shortSide;

    float2 uv = (2.0 * fragCoord - iResolution) / shortSide;
    uv.y = -uv.y;

    float radius = length(uv);
    float voice = clamp(iLevel, 0.0, 1.0);
    float breath = 0.5 + 0.5 * sin(iTime * 1.35);
    float sphereRadius = 0.72;

    // Compact support: completely transparent before the canvas edges.
    if (radius >= 0.96) {
        return half4(0.0);
    }

    float haloWidth = 0.065 + 0.035 * voice;
    float haloDistance = max(radius - sphereRadius, 0.0);
    float haloFalloff = exp(-haloDistance * haloDistance / (haloWidth * haloWidth));
    float haloCutoff = 1.0 - smoothstep(0.87, 0.96, radius);
    float haloAlpha = (0.10 + 0.025 * breath + 0.17 * voice) * haloFalloff * haloCutoff;

    float3 haloColor = mix(MINT, AMBER, 0.72 * voice);
    float3 premultiplied = haloColor * haloAlpha;
    float alpha = haloAlpha;

    float sphereCoverage = 1.0 - smoothstep(sphereRadius - pixel, sphereRadius + pixel, radius);

    if (sphereCoverage > 0.0) {
        float2 sphereXY = uv / sphereRadius;
        float sphereZ = sqrt(max(1.0 - dot(sphereXY, sphereXY), 0.0));
        float3 sphereNormal = normalize(float3(sphereXY, sphereZ));

        float3 viewDirection = float3(0.0, 0.0, 1.0);
        float3 keyLight = normalize(float3(-0.55, 0.68, 0.70));
        float3 keyHalf = normalize(keyLight + viewDirection);
        float3 warmLight = normalize(float3(0.67, -0.63, 0.38));
        float3 warmHalf = normalize(warmLight + viewDirection);

        float sphereDiffuse = max(dot(sphereNormal, keyLight), 0.0);
        float facing = clamp(sphereNormal.z, 0.0, 1.0);
        float rim = pow(1.0 - facing, 3.0);

        float3 sphereColor = GREEN * (0.48 + 0.62 * sphereDiffuse);
        sphereColor += MINT * (0.018 + 0.032 * sphereDiffuse);

        float breathingScale = 1.0 + 0.008 * (2.0 * breath - 1.0);
        float crystalRadius = sphereRadius * 0.55 * breathingScale * (1.0 + 0.06 * voice);
        float inradius = crystalRadius * 0.794654472;

        float boundRadius = crystalRadius + pixel;
        float radialSquared = dot(uv, uv);

        // No raymarching outside the sphere disc or crystal bound.
        if (radius < sphereRadius && radialSquared < boundRadius * boundRadius) {
            float yaw = 0.38 + iTime * (0.18 + 0.045 * voice);
            float pitch = 0.24 + 0.15 * sin(iTime * 0.31) + 0.025 * voice * sin(iTime * 0.83);
            float4 rotation = float4(cos(yaw), sin(yaw), cos(pitch), sin(pitch));

            // Orthographic camera; transform the ray only once.
            float3 rayOrigin = toLocal(float3(uv, 1.5), rotation);
            float3 rayDirection = toLocal(float3(0.0, 0.0, -1.0), rotation);

            float boundZ = sqrt(max(boundRadius * boundRadius - radialSquared, 0.0));
            float nearT = 1.5 - boundZ;
            float farT = 1.5 + boundZ;
            float t = nearT;
            float epsilon = max(0.0006, pixel * 0.22);
            bool hit = false;

            // 32 surface steps + 8 volume samples = at most 40.
            for (int i = 0; i < 32; i++) {
                if (t > farT) {
                    break;
                }
                float3 p = rayOrigin + rayDirection * t;
                float distanceToCrystal = icoDistance(p, inradius);
                if (distanceToCrystal <= epsilon) {
                    hit = true;
                    break;
                }
                t += distanceToCrystal;
            }

            if (hit) {
                float3 surface = rayOrigin + rayDirection * t;
                float edgeGap = 0.0;
                float3 normal = icoGradient(surface, edgeGap);
                float3 localView = -rayDirection;
                float3 localLight = toLocal(keyLight, rotation);
                float3 localHalf = normalize(localLight + localView);
                float3 localWarm = toLocal(warmLight, rotation);
                float3 localWarmHalf = normalize(localWarm + localView);

                float diffuse = max(dot(normal, localLight), 0.0);
                float warmDiffuse = max(dot(normal, localWarm), 0.0);
                float faceFacing = clamp(dot(normal, localView), 0.0, 1.0);
                float faceFresnel = pow(1.0 - faceFacing, 5.0);

                float3 facetColor = mix(GREEN * 0.48, MINT, 0.09 + 0.40 * diffuse);
                facetColor += MINT * (0.07 + 0.33 * faceFresnel);
                facetColor += AMBER * warmDiffuse * (0.025 + 0.09 * voice);

                float facetSpecular = pow(max(dot(normal, localHalf), 0.0), 110.0);
                float warmSpecular = pow(max(dot(normal, localWarmHalf), 0.0), 80.0);
                facetColor += WHITE * facetSpecular * 0.80;
                facetColor += AMBER * warmSpecular * (0.08 + 0.28 * voice);

                // Adjacent planes agree at a geometric edge. Pixel-aware widths retain thin lines.
                float edgeWidth = pixel * (0.38 + 0.24 * voice);
                float edgeLine = 1.0 - smoothstep(0.0, edgeWidth, edgeGap);
                float edgeGlow = exp(-edgeGap / (pixel * (1.25 + 1.8 * voice)));
                float3 edgeColor = mix(MINT, AMBER, voice * 0.92);
                facetColor += edgeColor * (edgeLine * (0.58 + 0.30 * voice) + edgeGlow * (0.13 + 0.18 * voice));

                // Integrate a small emissive volume behind the front face (amber core).
                float coreRadius = crystalRadius * (0.20 + 0.12 * voice);
                float coreRadiusSquared = coreRadius * coreRadius;
                float volumeStep = max(farT - t, 0.0) / 8.0;
                float sampleT = t + 0.5 * volumeStep;
                float coreIntegral = 0.0;
                for (int j = 0; j < 8; j++) {
                    float3 samplePosition = rayOrigin + rayDirection * sampleT;
                    float interior = 1.0 - smoothstep(-pixel, pixel, icoDistance(samplePosition, inradius));
                    float density = exp(-dot(samplePosition, samplePosition) / coreRadiusSquared);
                    coreIntegral += density * interior * volumeStep;
                    sampleT += volumeStep;
                }
                coreIntegral /= max(coreRadius, 0.001);
                float coreLight = coreIntegral * (0.15 + 0.045 * breath + 0.70 * voice);
                float3 coreColor = mix(AMBER, WHITE, 0.13 * voice);
                facetColor += coreColor * coreLight * (0.84 - 0.32 * faceFresnel);

                // Retain depth and green absorption through the glass.
                sphereColor = mix(sphereColor, facetColor, 0.88);
            }
        }

        // Front glass reflections sit over the embedded crystal.
        float broadReflection = pow(max(dot(sphereNormal, keyHalf), 0.0), 15.0);
        float crispReflection = pow(max(dot(sphereNormal, keyHalf), 0.0), 240.0);
        float warmReflection = pow(max(dot(sphereNormal, warmHalf), 0.0), 22.0);

        sphereColor += MINT * rim * (0.42 + 0.055 * breath);
        sphereColor += mix(MINT, WHITE, 0.60) * broadReflection * 0.105;
        sphereColor += WHITE * crispReflection * 0.80;
        sphereColor += AMBER * warmReflection * (0.035 + 0.20 * voice);

        // A fine luminous outline on the glass silhouette.
        float rimDistance = abs(radius - sphereRadius);
        float rimLine = 1.0 - smoothstep(0.0, pixel * 1.4, rimDistance);
        sphereColor += MINT * rimLine * 0.12;

        sphereColor = clamp(sphereColor, 0.0, 1.0);

        float bodyAlpha = sphereCoverage * (0.86 + 0.10 * rim);
        premultiplied = sphereColor * bodyAlpha + premultiplied * (1.0 - bodyAlpha);
        alpha = bodyAlpha + alpha * (1.0 - bodyAlpha);
    }

    float a = clamp(alpha, 0.0, 1.0);
    float3 rgb = premultiplied / max(a, 0.00001);
    return half4(rgb * a, a);
}
"""

/** GPU 구슬: [level] 은 그리는 때 읽는다(부모를 다시 구성하지 않음). API 33 아래·컴파일 실패면 [fallback] */
@Composable
internal fun OrbShader(level: () -> Float, modifier: Modifier, fallback: @Composable () -> Unit) {
    Box(modifier) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && orbShaderOk()) OrbApi33(level) else fallback()
    }
}

@Composable
private fun orbShaderOk(): Boolean = remember {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runCatching { RuntimeShader(ORB_AGSL) }.onFailure {
        android.util.Log.w(ai.meeos.tago.app.AppLog.TAG, "구슬 셰이더 컴파일 실패 — 캔버스 판으로: $it")
    }.isSuccess
}

@Composable
private fun rememberOrbTime(): State<Float> {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (isActive) withFrameMillis { now -> seconds.floatValue = (now - start).toFloat() * 0.001f }
    }
    return seconds
}

/** 마이크 갱신(0.08초) 사이를 그리기에서만 부드럽게 잇는다 — Compose 상태가 아닌 보통 필드 */
private class OrbEnvelope {
    private var previousTime = Float.NaN
    private var value = 0f
    fun sample(time: Float, input: Float): Float {
        val target = if (input.isFinite()) input.coerceIn(0f, 1f) else 0f
        val dt = if (previousTime.isFinite()) (time - previousTime).coerceIn(0f, 0.1f) else 1f / 60f
        previousTime = time
        val tau = if (target > value) 0.045f else 0.12f
        value += (target - value) * (1f - exp(-dt / tau))
        return value
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun OrbApi33(level: () -> Float) {
    val time = rememberOrbTime()
    val currentLevel = rememberUpdatedState(level)
    val envelope = remember { OrbEnvelope() }
    val shader = remember { RuntimeShader(ORB_AGSL) }   // 구슬마다 한 번만 컴파일
    Box(Modifier.fillMaxSize().drawWithCache {
        val brush = ShaderBrush(shader)
        onDrawBehind {
            val t = time.value
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("iTime", t)
            shader.setFloatUniform("iLevel", envelope.sample(t, currentLevel.value()))
            drawRect(brush = brush)
        }
    })
}
