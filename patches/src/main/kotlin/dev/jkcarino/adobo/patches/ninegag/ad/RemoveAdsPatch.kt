package dev.jkcarino.adobo.patches.ninegag.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import app.morphe.util.returnBoxedBooleanEarly
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import dev.jkcarino.adobo.patches.all.contentblocker.hosts.HostsBlocker
import dev.jkcarino.adobo.patches.all.contentblocker.hosts.HostsBlockerConfig
import dev.jkcarino.adobo.patches.all.contentblocker.hosts.baseHostsBlockerPatch
import dev.jkcarino.adobo.patches.ninegag.shared.COMPATIBILITY_NINEGAG

@Suppress("unused")
val removeAdsPatch = bytecodePatch(
    name = "Remove 9GAG's ads, trackers, and analytics",
    description = "Removes ads, trackers, and analytics in the 9GAG app."
) {
    compatibleWith(COMPATIBILITY_NINEGAG)

    dependsOn(
        baseHostsBlockerPatch {
            HostsBlockerConfig(
                hostsBlocker = HostsBlocker.fromString(AD_HOSTS)
            )
        },
        hideAdContainersPatch
    )

    execute {
        AdBlockReasonFlowFingerprint.methodOrNull?.let { flowMethod ->
            val evaluatorReference = flowMethod.instructions
                .asSequence()
                .mapNotNull { it.getReference<MethodReference>() }
                .singleOrNull { reference ->
                    val parameters = reference.parameterTypes.map { it.toString() }
                    reference.returnType.startsWith("L") &&
                        parameters.size == 5 &&
                        parameters[0] == "Z" &&
                        parameters[1] == "Z" &&
                        parameters[2].startsWith("L") &&
                        parameters[3] == "Z" &&
                        parameters[4].startsWith("L")
                }
                ?: throw PatchException("Could not resolve 9GAG's ad blocker reason evaluator")

            val enumConstructor = mutableClassDefBy(evaluatorReference.returnType)
                .methods
                .single { it.name == "<clinit>" }
            val subscriptionStringIndex = enumConstructor.instructions.indexOfFirst { instruction ->
                instruction.getReference<StringReference>()?.string == "Subscription"
            }
            if (subscriptionStringIndex < 0) {
                throw PatchException("Could not find 9GAG's Subscription ad blocker reason")
            }

            val subscriptionField = enumConstructor.instructions
                .asSequence()
                .drop(subscriptionStringIndex + 1)
                .firstNotNullOfOrNull { instruction ->
                    if (instruction.opcode == Opcode.SPUT_OBJECT) {
                        instruction.getReference<FieldReference>()?.takeIf { field ->
                            field.type == evaluatorReference.returnType
                        }
                    } else {
                        null
                    }
                }
                ?: throw PatchException("Could not resolve 9GAG's Subscription ad blocker reason")

            evaluatorReference.getMutableMethod().addInstructions(
                index = 0,
                smaliInstructions = """
                    sget-object p0, ${subscriptionField.definingClass}->${subscriptionField.name}:${subscriptionField.type}
                    return-object p0
                """
            )
        } ?: run {
            LegacyAdGateFingerprint.method.returnEarly(false)
            LegacyRuntimeAdGateFingerprint.method.returnBoxedBooleanEarly(false)
        }
    }
}
