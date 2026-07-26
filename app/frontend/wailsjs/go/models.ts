export namespace instance {
	
	export class ReplacerEntry {
	    feature: string;
	    chance: number;
	
	    static createFrom(source: any = {}) {
	        return new ReplacerEntry(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.feature = source["feature"];
	        this.chance = source["chance"];
	    }
	}
	export class Replacer {
	    vanillaId: string;
	    entries: ReplacerEntry[];
	    mode?: string;
	
	    static createFrom(source: any = {}) {
	        return new Replacer(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.vanillaId = source["vanillaId"];
	        this.entries = this.convertValues(source["entries"], ReplacerEntry);
	        this.mode = source["mode"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	
	export class Settings {
	    autoStartOnLaunch: boolean;
	    recentProjects: string[];
	
	    static createFrom(source: any = {}) {
	        return new Settings(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.autoStartOnLaunch = source["autoStartOnLaunch"];
	        this.recentProjects = source["recentProjects"];
	    }
	}

}

export namespace main {
	
	export class AssetsPayload {
	    version: string;
	    baseURL: string;
	    ready: boolean;
	    error: string;
	
	    static createFrom(source: any = {}) {
	        return new AssetsPayload(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.version = source["version"];
	        this.baseURL = source["baseURL"];
	        this.ready = source["ready"];
	        this.error = source["error"];
	    }
	}
	export class ProjectInfo {
	    path: string;
	    name: string;
	
	    static createFrom(source: any = {}) {
	        return new ProjectInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.name = source["name"];
	    }
	}
	export class StatusPayload {
	    phase: string;
	    message: string;
	    port: number;
	    token: string;
	
	    static createFrom(source: any = {}) {
	        return new StatusPayload(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.phase = source["phase"];
	        this.message = source["message"];
	        this.port = source["port"];
	        this.token = source["token"];
	    }
	}

}

