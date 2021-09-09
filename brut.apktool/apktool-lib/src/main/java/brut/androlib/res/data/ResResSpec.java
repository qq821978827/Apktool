/*
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.androlib.res.data;

import brut.androlib.AndrolibException;
import brut.androlib.err.UndefinedResObjectException;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 资源（规格）说明
 * Res标签的属性
 */
public class ResResSpec {
    /**
     * 资源的ID
     */
    private final ResID mId;
    /**
     * 资源文件名
     * 标签的属性name
     */
    private final String mName;
    /**
     * Resource.arsc 里面的ResPackage
     */
    private final ResPackage mPackage;
    /**
     * 资源类型说明
     */
    private final ResTypeSpec mType;
    /**
     * 资源配置标志与 ResResource 的映射
     */
    private final Map<ResConfigFlags, ResResource> mResources = new LinkedHashMap<ResConfigFlags, ResResource>();

    /**
     * @param id   资源的ID值
     * @param name 资源名字 标签的name属性
     * @param pkg  ResPackage
     * @param type 资源类型规格
     */
    public ResResSpec(ResID id, String name, ResPackage pkg, ResTypeSpec type) {
        this.mId = id;
        String cleanName;

//       从ResResSpec里面获取ResResSpec
        ResResSpec resResSpec = type.getResSpecUnsafe(name);
        if (resResSpec != null) {
            cleanName = String.format("APKTOOL_DUPLICATE_%s_%s", type.toString(), id.toString());
        } else {
            cleanName = ((name == null || name.isEmpty()) ? ("APKTOOL_DUMMYVAL_" + id.toString()) : name);
        }

        this.mName = cleanName;
        this.mPackage = pkg;
        this.mType = type;
    }

    /**
     * ResResource 集合
     *
     * @return Set<ResResource>
     */
    public Set<ResResource> listResources() {
        return new LinkedHashSet<ResResource>(mResources.values());
    }

    public ResResource getResource(ResType config) throws AndrolibException {
        return getResource(config.getFlags());
    }

    public ResResource getResource(ResConfigFlags config) throws AndrolibException {
        ResResource res = mResources.get(config);
        if (res == null) {
            throw new UndefinedResObjectException(String.format("resource: spec=%s, config=%s", this, config));
        }
        return res;
    }

    public ResResource getDefaultResource() throws AndrolibException {
        return getResource(new ResConfigFlags());
    }

    public boolean hasDefaultResource() {
        return mResources.containsKey(new ResConfigFlags());
    }

    public String getFullName(ResPackage relativeToPackage, boolean excludeType) {
        return getFullName(getPackage().equals(relativeToPackage), excludeType);
    }

    public String getFullName(boolean excludePackage, boolean excludeType) {
        return (excludePackage ? "" : getPackage().getName() + ":")
            + (excludeType ? "" : getType().getName() + "/") + getName();
    }

    /**
     * 资源ID
     *
     * @return ResID
     */
    public ResID getId() {
        return mId;
    }

    /**
     * 获取资源文件名
     * <p>
     * xml标签的属性名
     *
     * @return String
     */
    public String getName() {
        return StringUtils.replace(mName, "\"", "q");
    }

    /**
     * 获取 ResPackage
     *
     * @return ResPackage
     */
    public ResPackage getPackage() {
        return mPackage;
    }

    /**
     * 获取资源类型说明
     *
     * @return ResTypeSpec
     */
    public ResTypeSpec getType() {
        return mType;
    }

    /**
     * 是否Dummy ResSpec
     *
     * @return boolean
     */
    public boolean isDummyResSpec() {
        return getName().startsWith("APKTOOL_DUMMY_");
    }

    public void addResource(ResResource res) throws AndrolibException {
        addResource(res, false);
    }

    /**
     * 添加ResResource
     *
     * @param res       ResResource
     * @param overwrite 是否重写
     * @throws AndrolibException 自定义异常
     */
    public void addResource(ResResource res, boolean overwrite) throws AndrolibException {
        ResConfigFlags flags = res.getConfig().getFlags();
        if (mResources.put(flags, res) != null && !overwrite) {
            throw new AndrolibException(String.format("Multiple resources: spec=%s, config=%s", this, flags));
        }
    }

    @Override
    public String toString() {
        return mId.toString() + " " + mType.toString() + "/" + mName;
    }
}
