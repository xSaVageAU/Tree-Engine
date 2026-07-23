export namespace instance {
	
	export class Settings {
	    autoStartOnLaunch: boolean;
	
	    static createFrom(source: any = {}) {
	        return new Settings(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.autoStartOnLaunch = source["autoStartOnLaunch"];
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

